/*
 * All content copyright Terracotta, Inc., unless otherwise indicated. All rights reserved.
 */
package com.tc.objectserver.impl;

import com.tc.object.ObjectID;
import com.tc.objectserver.api.EvictableMap;
import com.tc.objectserver.api.ServerMapEvictionManager;
import com.tc.objectserver.l1.impl.ClientObjectReferenceSet;
import com.tc.objectserver.l1.impl.ClientObjectReferenceSetChangedListener;
import java.util.Collections;
import java.util.Map;

/**
 * This trigger is invoked by a server map with the size of the map goes over 
 * the max count + some overshoot count ( default is 15% of the max count and is 
 * set via TCProperty ehcache.storageStrategy.dcv2.eviction.overshoot ) and attempts
 * to bring the size of the cache to the max capacity
 * 
 * @author mscott
 */
public class CapacityEvictionTrigger extends AbstractEvictionTrigger implements ClientObjectReferenceSetChangedListener {
    
    private boolean aboveCapacity = true;
    private int count = 0;
    private int clientSetCount = 0;
    private final ServerMapEvictionManager mgr;
    private ClientObjectReferenceSet clientSet;

    public CapacityEvictionTrigger(ServerMapEvictionManager mgr, ObjectID oid) {
        super(oid);
        this.mgr = mgr;
    }

    @Override
    public boolean startEviction(EvictableMap map) {
  //  capacity eviction ignores underlying strategy b/c map.startEviction has already been called
        if ( !map.isEvicting() ) {
            throw new AssertionError("map is not in evicting state");
        }
        int max = map.getMaxTotalCount();
        
        if ( max != 0 && map.getSize() > max ) {
            return true;
        } else {
            map.evictionCompleted();
        }
        
        aboveCapacity = false;
        return false;
    }
    
    @Override
    public void completeEviction(EvictableMap map) {
 //  only if you sampled nothing, complete eviction, else actual eviction stage
 //  will take care of it.
        if ( count == 0 ) {
            super.completeEviction(map);
        }
    }
            
    @Override
    public Map collectEvictonCandidates(final int max, final EvictableMap map, final ClientObjectReferenceSet clients) {
   // lets try and get smarter about this in the future but for now, just bring it back to capacity
        final int size = map.getSize();
        final int sample = size - max;
        if ( max == 0 ) {
            throw new AssertionError("triggers should never start evicting a pinned cache or store");
        }
        if ( sample <= 0 ) {
            return Collections.emptyMap();
        }
        Map samples = map.getRandomSamples(sample, clients);
        count = samples.size();
 // didn't get the sample count we wanted.  wait for a clientobjectidset refresh, only once and try it again
        if ( count < sample ) {
            clients.addReferenceSetChangeListener(this);
            clientSetCount = clients.size();
            clientSet = clients;
        }
        return samples;
    } 
    
     @Override
    public void notifyReferenceSetChanged() {
       mgr.doEvictionOn(new AbstractEvictionTrigger(getId()) {
            private int sampleCount = 0;
            private boolean wasOver = true;
            private int clientSetCount = 0;

            @Override
            public boolean startEviction(EvictableMap map) {
                if ( map.getSize() <= map.getMaxTotalCount() ) {
                    wasOver = false;
                    clientSet.removeReferenceSetChangeListener(CapacityEvictionTrigger.this);
                    return false;
                } else {
                    return super.startEviction(map);
                }
            }
            
            @Override
            public void completeEviction(EvictableMap map) {
         //  only if you sampled nothing, complete eviction, else actual eviction stage
         //  will take care of it.
                if ( sampleCount == 0 ) {
                    super.completeEviction(map);
                }
            }
            
            @Override
            public Map collectEvictonCandidates(int max, EvictableMap map, ClientObjectReferenceSet clients) {
                final int grab = map.getSize() - max;
                Map sample;
                if ( grab > 0 ) {
                    sample = map.getRandomSamples(grab, clients);
                    clientSetCount = clients.size();
                } else {
                    sample = Collections.emptyMap();
                }
                sampleCount = sample.size();
                if ( sampleCount >= grab) {
                    clients.removeReferenceSetChangeListener(CapacityEvictionTrigger.this);
                }
                return sample;
            }

            @Override
            public String toString() {
                return "ClientReferenceSetRefreshCapacityEvictor{wasover="  + wasOver 
                        + " count=" + sampleCount
                        + " clientset=" + clientSet + "}";
            }
            
            

        });
    }
                 
    @Override
    public String toString() {
        return "CapacityEvictionTrigger{count=" 
                + count + ", was above capacity=" 
                + aboveCapacity + ", client set=" 
                + clientSetCount + '}';
    }

}
