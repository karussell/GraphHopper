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

import com.graphhopper.coll.GHBitSet;
import com.graphhopper.coll.MapEntry;
import com.graphhopper.routing.DijkstraBidirectionRef;
import com.graphhopper.routing.subnetwork.SubnetworkStorage;
import com.graphhopper.routing.subnetwork.TarjansSCCAlgorithm;
import com.graphhopper.routing.util.*;
import com.graphhopper.storage.*;
import com.graphhopper.util.EdgeIterator;
import com.graphhopper.util.EdgeIteratorState;
import gnu.trove.list.array.TIntArrayList;
import gnu.trove.map.TIntObjectMap;
import gnu.trove.procedure.TIntObjectProcedure;
import gnu.trove.procedure.TIntProcedure;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class stores the landmark nodes and the weights from and to all other nodes in every
 * subnetwork. This data is created to apply a speed-up for path calculation but at the same times
 * stays flexible to per-request changes. The class is safe for usage from multiple reading threads
 * across algorithms.
 *
 * @author Peter Karich
 */
public class LandmarkStorage implements Storable<LandmarkStorage> {
    private static final Logger LOGGER = LoggerFactory.getLogger(LandmarkStorage.class);
    private static final int UNSET_SUBNETWORK = -1;
    // one node has an associated landmark information ('one landmark row'): the forward and backward weight
    private long LM_ROW_LENGTH;
    private int landmarks;
    private final int FROM_OFFSET;
    private final int TO_OFFSET;
    private final DataAccess landmarkWeightDA;
    /* every subnetwork has its own landmark mapping but the count of landmarks is always the same */
    private final List<int[]> landmarkIDs;
    private double factor = 1;
    private final static double DOUBLE_MLTPL = 1.0e6;
    // TODO use just Graph but for TarjansSCCAlgorithm we need GraphHopperStorage
    private final GraphHopperStorage ghStorage;
    private final FlagEncoder encoder;
    private final Weighting weighting;
    private Weighting lmSelectionWeighting;
    private final TraversalMode traversalMode;
    private boolean initialized;
    private int minimumNodes = 500_000;
    private SubnetworkStorage subnetworkStorage;

    public LandmarkStorage(GraphHopperStorage ghStorage, Directory dir, int landmarks, FlagEncoder encoder,
                           Weighting weighting, TraversalMode traversalMode) {
        this.ghStorage = ghStorage;
        this.encoder = encoder;
        this.weighting = weighting;
        this.lmSelectionWeighting = new ShortestWeighting(encoder);

        this.traversalMode = traversalMode;
        final String name = AbstractWeighting.weightingToFileName(weighting);
        this.landmarkWeightDA = dir.find("landmarks_" + name);

        this.landmarks = landmarks;
        // one short per landmark and two directions => 2*2 byte
        this.LM_ROW_LENGTH = landmarks * 4;
        this.FROM_OFFSET = 0;
        this.TO_OFFSET = 2;
        this.landmarkIDs = new ArrayList<>();
        this.subnetworkStorage = new SubnetworkStorage(dir, "landmarks_" + name);
    }

    public void setMinimumNodes(int minimumNodes) {
        this.minimumNodes = minimumNodes;
    }

    SubnetworkStorage getSubnetworkStorage() {
        return subnetworkStorage;
    }

    /**
     * This weighting is used for the selection heuristic and is per default 'shortest' leading to a
     * much better distribution of the landmarks compared to 'fastest'.
     */
    public void setLMSelectionWeighting(Weighting lmSelectionWeighting) {
        this.lmSelectionWeighting = lmSelectionWeighting;
    }

    boolean isInitialized() {
        return initialized;
    }

    /**
     * This method calculates the landmarks and initial weightings to & from them.
     */
    public void createLandmarks() {
        if (isInitialized())
            throw new IllegalStateException("Initialize the landmark storage once!");

        // fill 'from' and 'to' weights with maximum value
        long maxBytes = (long) ghStorage.getNodes() * LM_ROW_LENGTH;
        this.landmarkWeightDA.create(2000);
        this.landmarkWeightDA.ensureCapacity(maxBytes);

        for (long pointer = 0; pointer < maxBytes; pointer += 2) {
            landmarkWeightDA.setShort(pointer, (short) SHORT_INFINITY);
        }

        // introduce a factor to store weight without loosing too much precision 
        // AND making it compatible with weighting.calcWeight
        double distanceInMeter = 6000 * 1000;
        double weightMax = weighting.getMinWeight(distanceInMeter);
        // 'to' and 'from' fit into 32 bit => 16 bit for each of them => 65536
        factor = weightMax / (1 << 16);

        LOGGER.info("creating landmarks for subnetworks with nodeCount>" + minimumNodes + ", weightMax:" + weightMax + ", factor:" + factor);

        // special subnetwork 0
        int[] empty = new int[landmarks];
        Arrays.fill(empty, -1);
        landmarkIDs.add(empty);

        // Currently we the restrictive two-direction edge filter to count nodes and detect subnetworks (roughly)
        // should we use Tarjan algorithm to be more precise?
        byte[] subnetworks = new byte[ghStorage.getNodes()];
        Arrays.fill(subnetworks, (byte) UNSET_SUBNETWORK);

        // 0 should only be used if subnetwork is too small
        int nextStartNode = 0;
        while (nextStartNode >= 0) {
            nextStartNode = createLandmarks(nextStartNode, subnetworks);
        }

        int subnetworkCount = landmarkIDs.size();
        // store all landmark node IDs and one int for the factor itself.
        this.landmarkWeightDA.ensureCapacity(maxBytes /* landmark weights */ + subnetworkCount * landmarks /* landmark mapping per subnetwork */);

        // calculate offset to point into landmark mapping
        long bytePos = maxBytes;
        for (int[] landmarks : landmarkIDs) {
            for (int lmNodeId : landmarks) {
                landmarkWeightDA.setInt(bytePos, lmNodeId);
                bytePos += 4L;
            }
        }

        landmarkWeightDA.setHeader(0 * 4, ghStorage.getNodes());
        landmarkWeightDA.setHeader(1 * 4, landmarks);
        landmarkWeightDA.setHeader(2 * 4, subnetworkCount);
        if (factor * DOUBLE_MLTPL > Integer.MAX_VALUE)
            throw new UnsupportedOperationException("landmark weight factor cannot be bigger than Integer.MAX_VALUE " + factor * DOUBLE_MLTPL);
        landmarkWeightDA.setHeader(3 * 4, (int) Math.round(factor * DOUBLE_MLTPL));

        // serialize fast byte[] into DataAccess
        subnetworkStorage.create(ghStorage.getNodes());
        for (int nodeId = 0; nodeId < subnetworks.length; nodeId++) {
            subnetworkStorage.setSubnetwork(nodeId, subnetworks[nodeId]);
        }

        initialized = true;
    }

    /**
     * This method creates landmarks for the specified subnetwork (integer list)
     *
     * @return landmark mapping
     */
    private int createLandmarks(final int startNode, byte[] subnetworks) {
        final int subnetworkId = landmarkIDs.size();

        // 1. pick landmarks via shortest weighting for a better geographical spreading
        // 'fastest' has big problems with ferries (slow&very long) and allowing arbitrary weighting is too dangerous
        Weighting initWeighting = lmSelectionWeighting;
        Explorer explorer = new Explorer(ghStorage, this, encoder, initWeighting, traversalMode);
        explorer.initFrom(startNode, 0);
        // force landmarks being always accessible in both directions (restrictive two-direction edge filter)
        explorer.setFilter(true, true);
        explorer.runAlgo(true);

        if (explorer.getFromCount() < minimumNodes) {
            // too small subnetworks are initialized with special id==0
            explorer.setSubnetworks(subnetworks, 0, true);
        } else {

            int[] tmpLandmarkNodeIds = new int[landmarks];
            int logOffset = Math.max(1, tmpLandmarkNodeIds.length / 2);
            for (int lmIdx = 0; lmIdx < tmpLandmarkNodeIds.length; lmIdx++) {
                tmpLandmarkNodeIds[lmIdx] = explorer.getLastNode();
                explorer = new Explorer(ghStorage, this, encoder, initWeighting, traversalMode);
                explorer.setFilter(true, true);
                for (int j = 0; j < lmIdx + 1; j++) {
                    explorer.initFrom(tmpLandmarkNodeIds[j], 0);
                }
                explorer.runAlgo(true);

                if (lmIdx % logOffset == 0)
                    LOGGER.info("Finding landmarks [" + weighting + "]. "
                            + "Progress " + (int) (100.0 * lmIdx / tmpLandmarkNodeIds.length) + "%");
            }

            LOGGER.info("Finding landmarks for subnetwork " + subnetworkId + ". Progress 100% for subnetwork of size " + explorer.getVisitedNodes());

            // 2. calculate weights for all landmarks -> 'from' and 'to' weight
            for (int lmIdx = 0; lmIdx < tmpLandmarkNodeIds.length; lmIdx++) {
                int lm = tmpLandmarkNodeIds[lmIdx];
                explorer = new Explorer(ghStorage, this, encoder, weighting, traversalMode);
                explorer.initFrom(lm, 0);
                explorer.setFilter(false, true);
                explorer.runAlgo(true);
                explorer.initFroms(lmIdx, LM_ROW_LENGTH, FROM_OFFSET);

                // set subnetwork id to all explored nodes, but do this only for the first landmark
                // important here is that we do not use the more restrictive two-direction edge filter
                if (lmIdx == 0)
                    explorer.setSubnetworks(subnetworks, subnetworkId, true);

                explorer = new Explorer(ghStorage, this, encoder, weighting, traversalMode);
                explorer.initTo(lm, 0);
                explorer.setFilter(true, false);
                explorer.runAlgo(false);
                explorer.initTos(lmIdx, LM_ROW_LENGTH, TO_OFFSET);

                if (lmIdx == 0)
                    explorer.setSubnetworks(subnetworks, subnetworkId, false);

                if (lmIdx % logOffset == 0)
                    LOGGER.info("Creating landmarks weights [" + weighting + "]. "
                            + "Progress " + (int) (100.0 * lmIdx / tmpLandmarkNodeIds.length) + "%");
            }

            // TODO set weight to SHORT_MAX if entry has either no 'from' or no 'to' entry
            landmarkIDs.add(tmpLandmarkNodeIds);
        }

//        for (int nodeIdx = 0; nodeIdx < graph.getNodes(); nodeIdx++)
//        {
//            for (int lmIdx = 0; lmIdx < landmarkIDs.length; lmIdx++)
//            {
//                // just from weights
//                long pointer = nodeIdx * LM_ROW_LENGTH + lmIdx * 4;
//                if (((int) da.getShort(pointer) & 0x0000FFFF) == INFINITY)
//                    System.out.println("lm " + lmIdx + "\t node " + nodeIdx);
//            }
//        }

        for (int nodeId = startNode; nodeId < subnetworks.length; nodeId++) {
            if (subnetworks[nodeId] == UNSET_SUBNETWORK) {
                if(nodeId == startNode)
                    throw new IllegalArgumentException("Next start node cannot be the same " + nodeId);

                return nodeId;
            }
        }
        return -1;
    }

    /**
     * The factor is used to convert double values into more compact int values.
     */
    double getFactor() {
        if (!isInitialized())
            throw new IllegalStateException("Cannot return factor in uninitialized state");

        return factor;
    }

    /**
     * @return the weight from the landmark to the specified node. Where the landmark integer is not
     * a node ID but the internal index of the landmark array.
     */
    public int getFromWeight(int landmarkIndex, int node) {
        int res = (int) landmarkWeightDA.getShort((long) node * LM_ROW_LENGTH + landmarkIndex * 4 + FROM_OFFSET)
                & 0x0000FFFF;
        assert res >= 0 : "Negative to weight " + res + ", landmark index:" + landmarkIndex + ", node:" + node;
        if (res == SHORT_INFINITY)
            // TODO can happen if endstanding oneway
            // we should set a 'from' value to SHORT_MAX if the 'to' value was already set to find real bugs
            // and what to return? Integer.MAX_VALUE i.e. convert to Double.pos_infinity upstream?
            return SHORT_MAX;
        // throw new IllegalStateException("Do not call getFromWeight for wrong landmark[" + landmarkIndex + "]=" + landmarkIDs[landmarkIndex] + " and node " + node);
        // TODO if(res == MAX) fallback to beeline approximation!?

        return res;
    }

    /**
     * @return the weight from the specified node to the landmark (*as index*)
     */
    public int getToWeight(int landmarkIndex, int node) {
        int res = (int) landmarkWeightDA.getShort((long) node * LM_ROW_LENGTH + landmarkIndex * 4 + TO_OFFSET)
                & 0x0000FFFF;
        assert res >= 0 : "Negative to weight " + res + ", landmark index:" + landmarkIndex + ", node:" + node;
        if (res == SHORT_INFINITY)
            return SHORT_MAX;
//            throw new IllegalStateException("Do not call getToWeight for wrong landmark[" + landmarkIndex + "]=" + landmarkIDs[landmarkIndex] + " and node " + node);

        return res;
    }

    // Short.MAX_VALUE = 2^15-1 but we have unsigned short so we need 2^16-1
    private static final int SHORT_INFINITY = Short.MAX_VALUE * 2 + 1;
    // We have large values that do not fit into a short, use a specific maximum value
    private static final int SHORT_MAX = SHORT_INFINITY - 1;

    final void setWeight(long pointer, double value) {
        double tmpVal = value / factor;
        if (tmpVal > Integer.MAX_VALUE)
            throw new UnsupportedOperationException("Cannot store infinity explicitely, pointer=" + pointer + ", value: " + value);
        else
            landmarkWeightDA.setShort(pointer, (short) ((tmpVal >= SHORT_MAX) ? SHORT_MAX : tmpVal));
    }

    boolean isInfinity(long pointer) {
        return ((int) landmarkWeightDA.getShort(pointer) & 0x0000FFFF) == SHORT_INFINITY;
    }

    int calcWeight(EdgeIteratorState edge, boolean reverse) {
        return (int) (weighting.calcWeight(edge, reverse, EdgeIterator.NO_EDGE) / factor);
    }

    // From all available landmarks pick just a few active ones. TODO: we can change the landmark set, 
    // while we calculate the route but this requires resetting the map and queue in the algo itself! 
    // Still according to papers this should give a speed up
    void initActiveLandmarks(int fromNode, int toNode, int[] activeLandmarkIndices,
                             int[] activeFroms, int[] activeTos, boolean reverse) {
        if (fromNode < 0 || toNode < 0)
            throw new IllegalStateException("from " + fromNode + " and to "
                    + toNode + " nodes have to be 0 or positive to init landmarks");

        int subnetworkFrom = subnetworkStorage.getSubnetwork(fromNode);
        int subnetworkTo = subnetworkStorage.getSubnetwork(toNode);
        if (subnetworkFrom != subnetworkTo)
            throw new RuntimeException("Connection between locations not found. Different subnetworks " + subnetworkFrom + " vs. " + subnetworkTo);

        int[] tmpIDs = landmarkIDs.get(subnetworkFrom);

        // kind of code duplication to approximate
        List<Map.Entry<Integer, Integer>> list = new ArrayList<>(tmpIDs.length);
        for (int lmIndex = 0; lmIndex < tmpIDs.length; lmIndex++) {
            int fromWeight = getFromWeight(lmIndex, toNode) - getFromWeight(lmIndex, fromNode);
            int toWeight = getToWeight(lmIndex, fromNode) - getToWeight(lmIndex, toNode);

            list.add(new MapEntry<>(reverse
                    ? Math.max(-fromWeight, -toWeight)
                    : Math.max(fromWeight, toWeight), lmIndex));
        }

        Collections.sort(list, SORT_BY_WEIGHT);

        for (int i = 0; i < activeLandmarkIndices.length; i++) {
            activeLandmarkIndices[i] = list.get(i).getValue();
        }

        // store weight values of active landmarks in 'cache' arrays
        for (int i = 0; i < activeLandmarkIndices.length; i++) {
            int lmIndex = activeLandmarkIndices[i];
            activeFroms[i] = getFromWeight(lmIndex, toNode);
            activeTos[i] = getToWeight(lmIndex, toNode);
        }
    }

    public int getLandmarkCount() {
        return landmarks;
    }

    public int[] getLandmarks(int subnetwork) {
        return landmarkIDs.get(subnetwork);
    }

    public int getSubnetworksWithLandmarks() {
        return landmarkIDs.size();
    }

    public boolean isEmpty() {
        return landmarkIDs.isEmpty();
    }

    @Override
    public String toString() {
        String str = "";
        for (int[] ints : landmarkIDs) {
            if (!str.isEmpty())
                str += ", ";
            str += Arrays.toString(ints);
        }
        return str;
    }

    /**
     * @return the calculated landmarks as GeoJSON string.
     */
    String getLandmarksAsGeoJSON() {
        NodeAccess na = ghStorage.getNodeAccess();
        String str = "";
        for (int subnetwork = 1; subnetwork < landmarkIDs.size(); subnetwork++) {
            int[] array = landmarkIDs.get(subnetwork);
            for (int index : array) {
                if (!str.isEmpty())
                    str += ",";

                str += "{ \"type\": \"Feature\", \"geometry\": {\"type\": \"Point\", \"coordinates\": ["
                        + na.getLon(index) + ", " + na.getLat(index) + "]},";
                str += "  \"properties\": {\"node_index\": \"" + index + "\"}"
                        + "{\"subnetwork\": \"" + subnetwork + "\"}"
                        + "}";
            }
        }

        return "{ \"type\": \"FeatureCollection\", \"features\": [" + str + "]}";
    }

    @Override
    public boolean loadExisting() {
        if (isInitialized())
            throw new IllegalStateException("Cannot call PrepareLandmarks.loadExisting if already initialized");
        if (landmarkWeightDA.loadExisting()) {
            if (!subnetworkStorage.loadExisting())
                throw new IllegalStateException("landmark weights loaded but not the subnetworks!?");

            int nodes = landmarkWeightDA.getHeader(0 * 4);
            if (nodes != ghStorage.getNodes())
                throw new IllegalArgumentException("Cannot load landmark data as written for different graph storage with " + nodes + " nodes, not " + ghStorage.getNodes());
            landmarks = landmarkWeightDA.getHeader(1 * 4);
            int subnetworks = landmarkWeightDA.getHeader(2 * 4);
            factor = landmarkWeightDA.getHeader(3 * 4) / DOUBLE_MLTPL;
            LM_ROW_LENGTH = landmarks * 4;
            long maxBytes = LM_ROW_LENGTH * nodes;
            long bytePos = maxBytes;

            // in the first subnetwork 0 there are no landmark IDs stored
            for (int j = 0; j < subnetworks; j++) {
                int[] tmpLandmarks = new int[landmarks];
                for (int i = 0; i < tmpLandmarks.length; i++) {
                    tmpLandmarks[i] = landmarkWeightDA.getInt(bytePos);
                    bytePos += 4;
                }
                landmarkIDs.add(tmpLandmarks);
            }

            initialized = true;
            return true;
        }
        return false;
    }

    @Override
    public LandmarkStorage create(long byteCount) {
        throw new IllegalStateException("Do not call LandmarkStore.create directly");
    }

    @Override
    public void flush() {
        landmarkWeightDA.flush();
        subnetworkStorage.flush();
    }

    @Override
    public void close() {
        landmarkWeightDA.close();
        subnetworkStorage.close();
    }

    @Override
    public boolean isClosed() {
        return landmarkWeightDA.isClosed();
    }

    @Override
    public long getCapacity() {
        return landmarkWeightDA.getCapacity() + subnetworkStorage.getCapacity();
    }

    // TODO use DijkstraOneToMany for max speed but higher memory consumption if executed in parallel threads?
    private static class Explorer extends DijkstraBidirectionRef {
        private int lastNode;
        private boolean from;
        private final LandmarkStorage lms;

        public Explorer(Graph g, LandmarkStorage lms, final FlagEncoder encoder, Weighting weighting, TraversalMode tMode) {
            super(g, encoder, weighting, tMode);
            this.lms = lms;
        }

        public void setFilter(boolean bwd, boolean fwd) {
            outEdgeExplorer = graph.createEdgeExplorer(new DefaultEdgeFilter(super.flagEncoder, bwd, fwd));
            inEdgeExplorer = graph.createEdgeExplorer(new DefaultEdgeFilter(super.flagEncoder, bwd, fwd));
        }

        int getFromCount() {
            return bestWeightMapFrom.size();
        }

        int getToCount() {
            return bestWeightMapTo.size();
        }

        public int getLastNode() {
            return lastNode;
        }

        public void runAlgo(boolean from) {
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
        public boolean finished() {
            if (from) {
                lastNode = currFrom.adjNode;
                return finishedFrom;
            } else {
                lastNode = currTo.adjNode;
                return finishedTo;
            }
        }

        public void setSubnetworks(final byte[] subnetworks, final int subnetworkId, final boolean expectFresh) {
            if (subnetworkId > 127)
                throw new IllegalStateException("Too many subnetworks " + subnetworkId);

            TIntObjectMap<SPTEntry> map = from ? bestWeightMapFrom : bestWeightMapTo;
            map.forEachEntry(new TIntObjectProcedure<SPTEntry>() {
                @Override
                public boolean execute(int nodeId, SPTEntry b) {
                    if (expectFresh) {
                        int sn = subnetworks[nodeId];
                        if (sn != UNSET_SUBNETWORK)
                            throw new IllegalStateException("subnetworkId for node " + nodeId + " already set (" + sn + "). " +
                                    "Cannot change to " + subnetworkId);
                    }

                    subnetworks[nodeId] = (byte) subnetworkId;
                    return true;
                }
            });
        }

        public void initFroms(final int lmIdx, final long rowSize, final int offset) {
            bestWeightMapFrom.forEachEntry(new TIntObjectProcedure<SPTEntry>() {
                @Override
                public boolean execute(int nodeId, SPTEntry b) {
                    lms.setWeight(nodeId * rowSize + lmIdx * 4 + offset, b.weight);
                    return true;
                }
            });
        }

        public void initTos(final int lmIdx, final long rowSize, final int offset) {
            bestWeightMapTo.forEachEntry(new TIntObjectProcedure<SPTEntry>() {
                @Override
                public boolean execute(int nodeId, SPTEntry b) {
                    lms.setWeight(nodeId * rowSize + lmIdx * 4 + offset, b.weight);
                    return true;
                }
            });
        }
    }

    /**
     * Sort landmark by weight and let maximum weight come first, to pick best active landmarks.
     */
    final static Comparator<Map.Entry<Integer, Integer>> SORT_BY_WEIGHT = new Comparator<Map.Entry<Integer, Integer>>() {
        @Override
        public int compare(Map.Entry<Integer, Integer> o1, Map.Entry<Integer, Integer> o2) {
            return Integer.compare(o2.getKey(), o1.getKey());
        }
    };
}
