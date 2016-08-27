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
package com.graphhopper.routing.lm;

import com.graphhopper.coll.MapEntry;
import com.graphhopper.routing.DijkstraBidirectionRef;
import com.graphhopper.routing.util.*;
import com.graphhopper.storage.*;
import com.graphhopper.util.EdgeIterator;
import com.graphhopper.util.EdgeIteratorState;
import gnu.trove.procedure.TIntObjectProcedure;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Should be thread safe to being used from multiple threads across algorithms.
 *
 * @author Peter Karich
 */
public class LandmarkStorage implements Storable<LandmarkStorage>
{
    private static final Logger LOGGER = LoggerFactory.getLogger(LandmarkStorage.class);
    private final long LM_ROW_LENGTH;
    private final int FROM_OFFSET;
    private final int TO_OFFSET;
    private final DataAccess da;
    private final int landmarkIDs[];
    private double factor = 1;
    private final Graph graph;
    private final FlagEncoder encoder;
    private final Weighting weighting;
    private Weighting lmSelectionWeighting;
    private final TraversalMode traversalMode;
    private boolean initialized;

    public LandmarkStorage( Graph g, Directory dir, int landmarks, FlagEncoder encoder,
                            Weighting weighting, TraversalMode traversalMode )
    {
        this.graph = g;
        this.encoder = encoder;
        this.weighting = weighting;
        this.lmSelectionWeighting = new ShortestWeighting(encoder);

        this.traversalMode = traversalMode;
        final String name = AbstractWeighting.weightingToFileName(weighting);
        this.da = dir.find("landmarks_" + name);

        // one short per landmark direction and two directions => 2*2 byte
        this.LM_ROW_LENGTH = landmarks * 4;
        this.FROM_OFFSET = 0;
        this.TO_OFFSET = 2;
        this.landmarkIDs = new int[landmarks];
    }

    public void setLMSelectionWeighting( Weighting lmSelectionWeighting )
    {
        this.lmSelectionWeighting = lmSelectionWeighting;
    }

    boolean isInitialized()
    {
        return initialized;
    }

    /**
     * This method calculates the landmarks and initial weightings to & from them.
     */
    public void createLandmarks()
    {
        if (isInitialized())
            throw new IllegalStateException("Initialize the landmark storage once!");

        // fill 'from' and 'to' weights with maximum value
        long maxBytes = (long) graph.getNodes() * LM_ROW_LENGTH;
        this.da.create(2000);
        this.da.ensureCapacity(maxBytes);

        for (long pointer = 0; pointer < maxBytes; pointer += 2)
        {
            setWeight(pointer, Double.MAX_VALUE);
        }

        // 1. pick landmarks via shortest weighting for a better geographical spreading
        // 'fastest' has big problems with ferries (slow&very long) and allowing arbitrary weighting is too dangerous
        Weighting initWeighting = lmSelectionWeighting;
        Explorer explorer = new Explorer(graph, this, encoder, initWeighting, traversalMode);
        // select 'random' node 0
        // TODO loop over subnetworks and use landmark mapping to reuse existing DataAccess
        explorer.initFrom(0, 0);
        explorer.runAlgo(true);

        int logOffset = Math.max(1, landmarkIDs.length / 2);
        for (int lmIdx = 0; lmIdx < landmarkIDs.length; lmIdx++)
        {
            landmarkIDs[lmIdx] = explorer.getLastNode();
            explorer = new Explorer(graph, this, encoder, initWeighting, traversalMode);
            for (int j = 0; j < lmIdx + 1; j++)
            {
                explorer.initFrom(landmarkIDs[j], 0);
            }
            explorer.runAlgo(true);

            if (lmIdx % logOffset == 0)
                LOGGER.info("Finding landmarks [" + weighting + "]. "
                        + "Progress " + (int) (100.0 * lmIdx / landmarkIDs.length) + "%");
        }

        LOGGER.info("Finding landmarks. Progress 100% for subnetwork of size " + explorer.getVisitedNodes() + " TODO: for all subnetworks");
        // introduce a factor to store weight without loosing too much precision 
        // AND making it compatible with weighting.calcWeight
        double distance = 6000 * 1000;
        double weightMax = weighting.getMinWeight(distance);
        // 'to' and 'from' fit into 32 bit => 16 bit for each of them => 65536
        factor = weightMax / (1 << 16);

        // 2. calculate weights for all landmarks -> 'from' and 'to' weight
        for (int lmIdx = 0; lmIdx < landmarkIDs.length; lmIdx++)
        {
            int lm = landmarkIDs[lmIdx];
            explorer = new Explorer(graph, this, encoder, weighting, traversalMode);
            explorer.initFrom(lm, 0);
            explorer.runAlgo(true);
            explorer.initFroms(lmIdx, LM_ROW_LENGTH, FROM_OFFSET);

            explorer = new Explorer(graph, this, encoder, weighting, traversalMode);
            explorer.initTo(lm, 0);
            explorer.runAlgo(false);
            explorer.initTos(lmIdx, LM_ROW_LENGTH, TO_OFFSET);
            if (lmIdx % logOffset == 0)
                LOGGER.info("Creating landmarks weights [" + weighting + "]. "
                        + "Progress " + (int) (100.0 * lmIdx / landmarkIDs.length) + "%");
        }

        this.da.ensureCapacity(maxBytes + landmarkIDs.length * 4);
        long bytePos = maxBytes;
        for (int lmId : landmarkIDs)
        {
            da.setInt(bytePos, lmId);
            bytePos += 4L;
        }

        initialized = true;
    }

    double getFactor()
    {
        if (!isInitialized())
            throw new IllegalStateException("Cannot return factor in uninitialized state");

        return factor;
    }

    boolean isEmpty()
    {
        return landmarkIDs.length == 0;
    }

    /**
     * @return the node ids of the landmarks
     */
    int[] getLandmarks()
    {
        return landmarkIDs;
    }

    /**
     * @return the weight from the landmark (*as index*) to the specified node
     */
    public int getFromWeight( int landmarkIndex, int node )
    {
        int res = (int) da.getShort((long) node * LM_ROW_LENGTH + landmarkIndex * 4 + FROM_OFFSET)
                & 0x0000FFFF;
        assert res >= 0 : "Negative to weight " + res + ", landmark index:" + landmarkIndex + ", node:" + node;
        if (res == INFINITY)
            throw new IllegalStateException("Do not call getFromWeight for wrong landmark[" + landmarkIndex + "]=" + landmarkIDs[landmarkIndex] + " and node " + node);
        // TODO if(res == MAX) fallback to beeline approximation!?

        return res;
    }

    /**
     * @return the weight from the specified node to the landmark (*as index*)
     */
    public int getToWeight( int landmarkIndex, int node )
    {
        int res = (int) da.getShort((long) node * LM_ROW_LENGTH + landmarkIndex * 4 + TO_OFFSET)
                & 0x0000FFFF;
        assert res >= 0 : "Negative to weight " + res + ", landmark index:" + landmarkIndex + ", node:" + node;
        if (res == INFINITY)
            throw new IllegalStateException("Do not call getToWeight for wrong landmark[" + landmarkIndex + "]=" + landmarkIDs[landmarkIndex] + " and node " + node);

        return res;
    }

    // Short.MAX_VALUE = 2*15-1 but we have unsigned short so we need 2*16-1
    private static final int INFINITY = Short.MAX_VALUE * 2 + 1;
    // We have large values that do not fit into a short, use a specific maximum value
    private static final int MAX = INFINITY - 1;

    final void setWeight( long pointer, double val )
    {
        val = val / factor;
        if (val > Integer.MAX_VALUE)
            da.setShort(pointer, (short) INFINITY);
        else
            da.setShort(pointer, (short) ((val >= MAX) ? MAX : val));
    }

    boolean isInfinity( long pointer )
    {
        return ((int) da.getShort(pointer) & 0x0000FFFF) == INFINITY;
    }

    int calcWeight( EdgeIteratorState edge, boolean reverse )
    {
        return (int) (weighting.calcWeight(edge, reverse, EdgeIterator.NO_EDGE) / factor);
    }

    // From all available landmarks pick just a few active ones
    // TODO we can change the set while we calculate the route (should give also a speed up) but this requires resetting the map and queue in the algo itself!
    void initActiveLandmarks( int fromNode, int toNode, int[] activeLandmarkIndices,
                              int[] activeFroms, int[] activeTos, boolean reverse )
    {
        if (fromNode < 0 || toNode < 0)
            throw new IllegalStateException("from " + fromNode + " and to "
                    + toNode + " nodes have to be 0 or positive to init landmarks");

        // kind of code duplication to approximate
        List<Map.Entry<Integer, Integer>> list = new ArrayList<>(landmarkIDs.length);
        for (int lmIndex = 0; lmIndex < landmarkIDs.length; lmIndex++)
        {
            int fromWeight = getFromWeight(lmIndex, toNode) - getFromWeight(lmIndex, fromNode);
            int toWeight = getToWeight(lmIndex, fromNode) - getToWeight(lmIndex, toNode);

            list.add(new MapEntry<>(reverse
                    ? Math.max(-fromWeight, -toWeight)
                    : Math.max(fromWeight, toWeight), lmIndex));
        }

        Collections.sort(list, SORT_BY_WEIGHT);

        for (int i = 0; i < activeLandmarkIndices.length; i++)
        {
            activeLandmarkIndices[i] = list.get(i).getValue();
        }

        // store weight values of active landmarks in 'cache' arrays
        for (int i = 0; i < activeLandmarkIndices.length; i++)
        {
            int lmIndex = activeLandmarkIndices[i];
            activeFroms[i] = getFromWeight(lmIndex, toNode);
            activeTos[i] = getToWeight(lmIndex, toNode);
        }
    }

    public int getLandmarkCount()
    {
        return landmarkIDs.length;
    }

    @Override
    public String toString()
    {
        return Arrays.toString(landmarkIDs);
    }

    String getLandmarksAsGeoJSON()
    {

        NodeAccess na = graph.getNodeAccess();
        String str = "";
        for (int index : getLandmarks())
        {
            if (!str.isEmpty())
                str += ",";

            str += "{ \"type\": \"Feature\", \"geometry\": {\"type\": \"Point\", \"coordinates\": ["
                    + na.getLon(index) + ", " + na.getLat(index) + "]},";
            str += "  \"properties\": {\"node_index\": \"" + index + "\"}}";
        }

        return "{ \"type\": \"FeatureCollection\", \"features\": [" + str + "]}";
    }

    @Override
    public boolean loadExisting()
    {
        if (isInitialized())
            throw new IllegalStateException("Cannot call PrepareLandmarks.loadExisting if already initialized");
        if (da.loadExisting())
        {
            long maxBytes = LM_ROW_LENGTH * graph.getNodes();
            long bytePos = maxBytes;
            for (int i = 0; i < landmarkIDs.length; i++)
            {
                landmarkIDs[i] = da.getInt(bytePos);
                bytePos += 4;
            }
            initialized = true;
            return true;
        }
        return false;
    }

    @Override
    public LandmarkStorage create( long byteCount )
    {
        throw new IllegalStateException("Do not call LandmarkStore.create directly");
    }

    @Override
    public void flush()
    {
        da.flush();
    }

    @Override
    public void close()
    {
        da.close();
    }

    @Override
    public boolean isClosed()
    {
        return da.isClosed();
    }

    @Override
    public long getCapacity()
    {
        return da.getCapacity();
    }

    // TODO use DijkstraOneToMany for max speed but higher memory consumption if executed in parallel threads?
    private static class Explorer extends DijkstraBidirectionRef
    {
        private int lastNode;
        private boolean from;
        private final LandmarkStorage lms;

        public Explorer( Graph g, LandmarkStorage lms, FlagEncoder encoder, Weighting weighting, TraversalMode tMode )
        {
            super(g, encoder, weighting, tMode);
            this.lms = lms;
        }

        public int getLastNode()
        {
            return lastNode;
        }

        public void runAlgo( boolean from )
        {
            // no path should be calculated
            setUpdateBestPath(false);
            // set one of the bi directions as already finished
            if (from)
                finishedTo = true;
            else
                finishedFrom = true;
            this.from = from;
            super.runAlgo();
        }

        @Override
        public boolean finished()
        {
            if (from)
            {
                lastNode = currFrom.adjNode;
                return finishedFrom;
            } else
            {
                lastNode = currTo.adjNode;
                return finishedTo;
            }
        }

        public void initFroms( final int lmIdx, final long rowSize, final int offset )
        {
            bestWeightMapFrom.forEachEntry(new TIntObjectProcedure<SPTEntry>()
            {
                @Override
                public boolean execute( int nodeId, SPTEntry b )
                {
                    lms.setWeight(nodeId * rowSize + lmIdx * 4 + offset, b.weight);
                    return true;
                }
            });
        }

        public void initTos( final int lmIdx, final long rowSize, final int offset )
        {
            bestWeightMapTo.forEachEntry(new TIntObjectProcedure<SPTEntry>()
            {
                @Override
                public boolean execute( int nodeId, SPTEntry b )
                {
                    lms.setWeight(nodeId * rowSize + lmIdx * 4 + offset, b.weight);
                    return true;
                }
            });
        }
    }

    /**
     * sort by weight and let maximum weight come first
     */
    final static Comparator<Map.Entry<Integer, Integer>> SORT_BY_WEIGHT = new Comparator<Map.Entry<Integer, Integer>>()
    {
        @Override
        public int compare( Map.Entry<Integer, Integer> o1, Map.Entry<Integer, Integer> o2 )
        {
            return Integer.compare(o2.getKey(), o1.getKey());
        }
    };
}
