/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright notice.  All rights reserved.
 */

package com.tctest.spring.bean;

import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationListener;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;


public class SimpleListener implements ApplicationListener {
    private transient List events = new ArrayList();
    private Date lastEventTime;
   
    public int size() {
      synchronized(events) {
        return events.size();
      }
    }
    
    public void clear() {
      synchronized (events) {
        events.clear();
      }
    }
    
    // ApplicationListener
    
    public void onApplicationEvent(ApplicationEvent event) {
      if(event instanceof SingletonEvent) {
        System.out.println("Got SingletonEvent: " + event);
        synchronized (events) {
          this.events.add(event);
        }
        this.lastEventTime = new Date();
      } else {
        System.out.println("Got some other kind of event: " + event);
      }
    }
    
    public Date getLastEventTime() {
        return lastEventTime;
    }
}

