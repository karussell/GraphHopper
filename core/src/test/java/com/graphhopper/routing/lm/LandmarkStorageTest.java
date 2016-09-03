/*
 *  Licensed to GraphHopper GmbH under one or more contributor
 *  license agreements. See the NOTICE file distributed with this work for 
 *  additional information regarding copyright ownership.
 * 
 *  GraphHopper GmbH licenses this file to you under the Apache License, 
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

import com.graphhopper.routing.util.*;
import com.graphhopper.storage.*;
import com.graphhopper.util.EdgeIteratorState;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * @author Peter Karich
 */
public class LandmarkStorageTest
{
    private Graph graph;
    private FlagEncoder encoder;
    private TraversalMode tm;

    @Before
    public void setUp()
    {
        encoder = new CarFlagEncoder();
        tm = TraversalMode.NODE_BASED;
        GraphHopperStorage tmp = new GraphHopperStorage(new RAMDirectory(),
                new EncodingManager(encoder), false, new GraphExtension.NoOpExtension());
        tmp.create(1000);
        graph = tmp;
    }

    @Test
    public void testInfinitWeight()
    {
        Directory dir = new RAMDirectory();
        EdgeIteratorState edge = graph.edge(0, 1);
        int res = new LandmarkStorage(graph, dir, 8, encoder, new FastestWeighting(encoder)
        {
            @Override
            public double calcWeight( EdgeIteratorState edgeState, boolean reverse, int prevOrNextEdgeId )
            {
                return Integer.MAX_VALUE * 2L;
            }
        }, TraversalMode.NODE_BASED).calcWeight(edge, false);
        assertEquals(Integer.MAX_VALUE, res);

        dir = new RAMDirectory();
        res = new LandmarkStorage(graph, dir, 8, encoder, new FastestWeighting(encoder)
        {
            @Override
            public double calcWeight( EdgeIteratorState edgeState, boolean reverse, int prevOrNextEdgeId )
            {
                return Double.POSITIVE_INFINITY;
            }
        }, TraversalMode.NODE_BASED).calcWeight(edge, false);
        assertEquals(Integer.MAX_VALUE, res);
    }

    @Test
    public void testSetGetWeight()
    {
        graph.edge(0, 1, 40, true);
        Directory dir = new RAMDirectory();
        DataAccess da = dir.find("landmarks_fastest_car");
        da.create(2000);

        LandmarkStorage lms = new LandmarkStorage(graph, dir, 4, encoder, new FastestWeighting(encoder), tm);
        // 2^16=65536, use -1 for infinity and -2 for maximum
        lms.setWeight(0, 65536);
        // reached maximum value but do not reset to 0 instead use 2^16-2
        assertEquals(65536 - 2, lms.getFromWeight(0, 0));
        lms.setWeight(0, 65535);
        assertEquals(65534, lms.getFromWeight(0, 0));
        lms.setWeight(0, 79999);
        assertEquals(65534, lms.getFromWeight(0, 0));

        da.setInt(0, Integer.MAX_VALUE);
        assertTrue(lms.isInfinity(0));        
        // for infinity return much bigger value
        // assertEquals(Integer.MAX_VALUE, lms.getFromWeight(0, 0));

        lms.setWeight(0, 79999);
        assertFalse(lms.isInfinity(0));
    }
}
