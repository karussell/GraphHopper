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
package com.graphhopper.routing.subnetwork;

import com.graphhopper.storage.DataAccess;
import com.graphhopper.storage.Directory;
import com.graphhopper.storage.Storable;

/**
 * @author Peter Karich
 */
public class SubnetworkStorage implements Storable<SubnetworkStorage>
{
    private final DataAccess da;

    public SubnetworkStorage( Directory dir, String postfix )
    {
        da = dir.find("subnetwork_" + postfix);
    }

    @Override
    public boolean loadExisting()
    {
        return da.loadExisting();
    }

    @Override
    public SubnetworkStorage create( long byteCount )
    {
        da.create(byteCount);
        return this;
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
}
