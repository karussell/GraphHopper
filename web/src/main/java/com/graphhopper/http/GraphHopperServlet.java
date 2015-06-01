/*
 *  Licensed to GraphHopper and Peter Karich under one or more contributor
 *  license agreements. See the NOTICE file distributed with this work for 
 *  additional information regarding copyright ownership.
 * 
 *  GraphHopper licenses this file to you under the Apache License, 
 *  Version 2.0 (the "License"); you may not use this file except in 
 *  compliance with the License. You may obtain a copy of the License at
 * 
 *       http://www.apache.org/licenses/LICENSE-2.0
 * 
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package com.graphhopper.http;

import com.graphhopper.GHRequest;
import com.graphhopper.GraphHopper;
import com.graphhopper.GHResponse;
import com.graphhopper.routing.util.FlagEncoder;
import com.graphhopper.routing.util.WeightingMap;
import com.graphhopper.util.*;
import com.graphhopper.util.shapes.GHPoint;
import java.io.IOException;
import java.io.StringWriter;
import java.util.*;
import java.util.Map.Entry;
import javax.inject.Inject;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import static javax.servlet.http.HttpServletResponse.*;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.json.JSONObject;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * Servlet to use GraphHopper in a remote application (mobile or browser). Attention: If type is
 * json it returns the points in GeoJson format (longitude,latitude) unlike the format "lat,lon"
 * used otherwise.
 * <p/>
 * @author Peter Karich
 */
public class GraphHopperServlet extends GHBaseServlet
{
    @Inject
    private GraphHopper hopper;
    @Inject
    private RouteSerializer routeSerializer;

    @Override
    public void doGet( HttpServletRequest req, HttpServletResponse res ) throws ServletException, IOException
    {
        try
        {
            writePath(req, res);
        } catch (IllegalArgumentException ex)
        {
            writeError(res, SC_BAD_REQUEST, ex.getMessage());
        } catch (Exception ex)
        {
            logger.error("Error while executing request: " + req.getQueryString(), ex);
            writeError(res, SC_INTERNAL_SERVER_ERROR, "Problem occured:" + ex.getMessage());
        }
    }

    void writePath( HttpServletRequest httpReq, HttpServletResponse httpRes ) throws Exception
    {
        List<GHPoint> infoPoints = getPoints(httpReq, "point");

        // we can reduce the path length based on the maximum differences to the original coordinates
        double minPathPrecision = getDoubleParam(httpReq, "way_point_max_distance", 1d);
        boolean writeGPX = "gpx".equalsIgnoreCase(getParam(httpReq, "type", "json"));
        boolean enableInstructions = writeGPX || getBooleanParam(httpReq, "instructions", true);
        boolean calcPoints = getBooleanParam(httpReq, "calc_points", true);
        boolean enableElevation = getBooleanParam(httpReq, "elevation", false);
        boolean pointsEncoded = getBooleanParam(httpReq, "points_encoded", true);

        String vehicleStr = getParam(httpReq, "vehicle", "CAR").toUpperCase();
        String weighting = getParam(httpReq, "weighting", "fastest");
        String algoStr = getParam(httpReq, "algorithm", "");
        String localeStr = getParam(httpReq, "locale", "en");

        StopWatch sw = new StopWatch().start();
        GHResponse ghRsp;
        if (!hopper.getEncodingManager().supports(vehicleStr))
        {
            ghRsp = new GHResponse().addError(new IllegalArgumentException("Vehicle not supported: " + vehicleStr));
        } else if (enableElevation && !hopper.hasElevation())
        {
            ghRsp = new GHResponse().addError(new IllegalArgumentException("Elevation not supported!"));
        } else
        {
            FlagEncoder algoVehicle = hopper.getEncodingManager().getEncoder(vehicleStr);
            GHRequest request = new GHRequest(infoPoints);

            initHints(request, httpReq.getParameterMap());
            request.setVehicle(algoVehicle.toString()).
                    setWeighting(weighting).
                    setAlgorithm(algoStr).
                    setLocale(localeStr).
                    getHints().
                    put("calcPoints", calcPoints).
                    put("instructions", enableInstructions).
                    put("wayPointMaxDistance", minPathPrecision);

            ghRsp = hopper.route(request);
        }

        float took = sw.stop().getSeconds();
        String infoStr = httpReq.getRemoteAddr() + " " + httpReq.getLocale() + " " + httpReq.getHeader("User-Agent");
        String logStr = httpReq.getQueryString() + " " + infoStr + " " + infoPoints + ", took:"
                + took + ", " + algoStr + ", " + weighting + ", " + vehicleStr;

        if (ghRsp.hasErrors())
            logger.error(logStr + ", errors:" + ghRsp.getErrors());
        else
            logger.info(logStr + ", distance: " + ghRsp.getDistance()
                    + ", time:" + Math.round(ghRsp.getMillis() / 60000f)
                    + "min, points:" + ghRsp.getPoints().getSize() + ", debug - " + ghRsp.getDebugInfo());

        if (writeGPX)
        {
            String xml = createGPXString(httpReq, httpRes, ghRsp);
            if (ghRsp.hasErrors())
            {
                httpRes.setStatus(SC_BAD_REQUEST);
                httpRes.getWriter().append(xml);
            } else
                writeResponse(httpRes, xml);
        } else
        {
            Map<String, Object> map = routeSerializer.toJSON(ghRsp, calcPoints, pointsEncoded,
                    enableElevation, enableInstructions);

            Object infoMap = map.get("info");
            if (infoMap != null)
                ((Map) infoMap).put("took", Math.round(took * 1000));

            if (ghRsp.hasErrors())
            {
                writeJsonError(httpRes, SC_BAD_REQUEST, new JSONObject(map));
            } else
                writeJson(httpReq, httpRes, new JSONObject(map));
        }
    }

    protected String createGPXString( HttpServletRequest req, HttpServletResponse res, GHResponse rsp )
            throws Exception
    {
        boolean includeElevation = getBooleanParam(req, "elevation", false);
        res.setCharacterEncoding("UTF-8");
        res.setContentType("application/xml");
        String trackName = getParam(req, "track", "GraphHopper Track");
        res.setHeader("Content-Disposition", "attachment;filename=" + "GraphHopper.gpx");
        String timeZone = getParam(req, "timezone", "GMT");
        long time = getLongParam(req, "millis", System.currentTimeMillis());
        if (rsp.hasErrors())
            return errorsToXML(rsp.getErrors());
        else
            return rsp.getInstructions().createGPX(trackName, time, timeZone, includeElevation);
    }

    String errorsToXML( List<Throwable> list )
    {
        try
        {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.newDocument();
            Element gpxElement = doc.createElement("gpx");
            gpxElement.setAttribute("creator", "GraphHopper");
            gpxElement.setAttribute("version", "1.1");
            doc.appendChild(gpxElement);

            Element mdElement = doc.createElement("metadata");
            gpxElement.appendChild(mdElement);

            Element errorsElement = doc.createElement("extensions");
            mdElement.appendChild(errorsElement);

            for (Throwable t : list)
            {
                Element error = doc.createElement("error");
                errorsElement.appendChild(error);
                error.setAttribute("message", t.getMessage());
                error.setAttribute("details", t.getClass().getName());
            }
            TransformerFactory transformerFactory = TransformerFactory.newInstance();
            Transformer transformer = transformerFactory.newTransformer();
            StringWriter writer = new StringWriter();
            transformer.transform(new DOMSource(doc), new StreamResult(writer));
            return writer.toString();
        } catch (Exception ex)
        {
            throw new RuntimeException(ex);
        }
    }

    protected List<GHPoint> getPoints( HttpServletRequest req, String key )
    {
        String[] pointsAsStr = getParams(req, key);
        final List<GHPoint> infoPoints = new ArrayList<GHPoint>(pointsAsStr.length);
        for (String str : pointsAsStr)
        {
            String[] fromStrs = str.split(",");
            if (fromStrs.length == 2)
            {
                GHPoint point = GHPoint.parse(str);
                if (point != null)
                {
                    infoPoints.add(point);
                }
            }
        }

        return infoPoints;
    }

    protected void initHints( GHRequest request, Map<String, String[]> parameterMap )
    {
        WeightingMap m = request.getHints();
        for (Entry<String, String[]> e : parameterMap.entrySet())
        {
            if (e.getValue().length == 1)
                m.put(e.getKey(), e.getValue()[0]);
        }
    }
}
