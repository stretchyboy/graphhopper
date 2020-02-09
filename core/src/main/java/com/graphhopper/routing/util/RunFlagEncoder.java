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
package com.graphhopper.routing.util;

import com.graphhopper.reader.ReaderWay;
import com.graphhopper.routing.weighting.PriorityWeighting;
import com.graphhopper.storage.IntsRef;
import com.graphhopper.util.EdgeIteratorState;
import com.graphhopper.util.Helper;
import com.graphhopper.util.PMap;
import com.graphhopper.util.PointList;

import java.util.TreeMap;

import static com.graphhopper.routing.profiles.RouteNetwork.*;
import static com.graphhopper.routing.util.PriorityCode.*;

/**
 * Defines bit layout for running ultras
 *
 * @author Peter Karich
 */
public class RunFlagEncoder extends FootFlagEncoder {
    static final int MIN_SPEED  = 3;
    static final int SLOW_SPEED = 5;
    static final int MEAN_SPEED = 8;
    static final int MAX_SPEED = 10;
    static final int FERRY_SPEED = 15;
    public RunFlagEncoder() {
        this(4, 1);
    }

    public RunFlagEncoder(PMap properties) {
        this((int) properties.getLong("speed_bits", 4),
                properties.getDouble("speed_factor", 1));
        this.setBlockFords(properties.getBool("block_fords", true));
    }

    public RunFlagEncoder(int speedBits, double speedFactor) {
        super(speedBits, speedFactor);

        routeMap.put(INTERNATIONAL, BEST.getValue());
        routeMap.put(NATIONAL, BEST.getValue());
        routeMap.put(REGIONAL, VERY_NICE.getValue());
        routeMap.put(LOCAL, VERY_NICE.getValue());

        /*I will almost certainly slow down as the event progresses.
        I will walk up all hills.
        I will trot down all hills but probably nothing over 10kph.
         I will run/walk the flats unlikely to get to more the 8kph.
          I don't want to attempt more than 70miles total I think*/

    }

    @Override
    public int getVersion() {
        return 5;
    }

    @Override
    void collect(ReaderWay way, TreeMap<Double, Integer> weightToPrioMap) {
        String highway = way.getTag("highway");
        if (way.hasTag("foot", "designated"))
            weightToPrioMap.put(100d, PREFER.getValue());

        double maxSpeed = getMaxSpeed(way);
        if (safeHighwayTags.contains(highway) || maxSpeed > 0 && maxSpeed <= 20) {
            weightToPrioMap.put(40d, PREFER.getValue());
            if (way.hasTag("tunnel", intendedValues)) {
                if (way.hasTag("sidewalk", sidewalksNoValues))
                    weightToPrioMap.put(40d, REACH_DEST.getValue());
                else
                    weightToPrioMap.put(40d, UNCHANGED.getValue());
            }
        } else if (maxSpeed > 50 || avoidHighwayTags.contains(highway)) {
            if (way.hasTag("sidewalk", sidewalksNoValues))
                weightToPrioMap.put(45d, WORST.getValue());
            else
                weightToPrioMap.put(45d, REACH_DEST.getValue());
        }

        if (way.hasTag("bicycle", "official") || way.hasTag("bicycle", "designated"))
            weightToPrioMap.put(44d, AVOID_IF_POSSIBLE.getValue());
    }
    // TODO : Different surface speeds
    // TODO : Avoid styles
    // TODO : How to do straightness (is it try the isocro network thing with this vehicle


    @Override
    public void applyWayTags(ReaderWay way, EdgeIteratorState edge) {
        PointList pl = edge.fetchWayGeometry(3);
        if (!pl.is3D())
            return;

        if (way.hasTag("tunnel", "yes") || way.hasTag("bridge", "yes") || way.hasTag("highway", "steps"))
            // do not change speed
            // note: although tunnel can have a difference in elevation it is unlikely that the elevation data is correct for a tunnel
            return;

        // Decrease the speed for ele increase (incline), and slightly decrease the speed for ele decrease (decline)
        double prevEle = pl.getElevation(0);
        double fullDistance = edge.getDistance();

        // for short edges an incline makes no sense and for 0 distances could lead to NaN values for speed, see #432
        if (fullDistance < 2)
            return;

        double eleDelta = Math.abs(pl.getElevation(pl.size() - 1) - prevEle);
        double slope = eleDelta / fullDistance;

        IntsRef edgeFlags = edge.getFlags();
        if ((accessEnc.getBool(false, edgeFlags) || accessEnc.getBool(true, edgeFlags)))
        {
            if( slope > 0.005) {
                // DONE : Nicky says walking up (so maybe use hike values.


                // see #1679 => v_hor=4.5km/h for horizontal speed; v_vert=2*0.5km/h for vertical speed (assumption: elevation < edge distance/4.5)
                // s_3d/v=h/v_vert + s_2d/v_hor => v = s_3d / (h/v_vert + s_2d/v_hor) = sqrt(s²_2d + h²) / (h/v_vert + s_2d/v_hor)
                // slope=h/s_2d=~h/2_3d              = sqrt(1+slope²)/(slope+1/4.5) km/h
                // maximum slope is 0.37 (Ffordd Pen Llech)

                float fSLOW_SPEED;
                fSLOW_SPEED = SLOW_SPEED;
                double newSpeed = Math.sqrt(1 + slope * slope) / (slope + 1 / fSLOW_SPEED);
                edge.set(avgSpeedEnc, Helper.keepIn(newSpeed, MIN_SPEED, MAX_SPEED));
            }
            else if(slope < - 0.005){
                // TODO : Downhill = 10khp ??
                float fMEAN_SPEED;
                fMEAN_SPEED = MEAN_SPEED;
                double newSpeed = Math.sqrt(1 + slope * slope) / (slope + 1 / fMEAN_SPEED);
                edge.set(avgSpeedEnc, Helper.keepIn(newSpeed, MIN_SPEED, MAX_SPEED));
            }

        }
    }

    @Override
    public boolean supports(Class<?> feature) {
        if (super.supports(feature))
            return true;

        return PriorityWeighting.class.isAssignableFrom(feature);
    }

    @Override
    public String toString() {
        return "run";
    }
}
