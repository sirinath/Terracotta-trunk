/**
 * All content copyright (c) 2003-2006 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.config.schema.setup;

import com.tc.exception.ImplementMe;
import com.terracottatech.config.Ha;
import com.terracottatech.config.Server;
import com.terracottatech.config.Servers;
import com.terracottatech.config.UpdateCheck;

public class TestL2S extends TestXmlObject implements Servers {

  private Server[] servers;

  public TestL2S(Server[] servers) {
    this.servers = servers;
  }

  public TestL2S() {
    this(null);
  }

  public void setServers(Server[] servers) {
    this.servers = servers;
  }

  public Server[] getServerArray() {
    return this.servers;
  }

  public Server getServerArray(int arg0) {
    return this.servers[arg0];
  }

  public int sizeOfServerArray() {
    return this.servers.length;
  }

  public void setServerArray(Server[] arg0) {
    throw new ImplementMe();
  }

  public void setServerArray(int arg0, Server arg1) {
    throw new ImplementMe();
  }

  public Server insertNewServer(int arg0) {
    throw new ImplementMe();
  }

  public Server addNewServer() {
    throw new ImplementMe();
  }

  public void removeServer(int arg0) {
    throw new ImplementMe();
  }

  public Ha addNewHa() {
    throw new ImplementMe();
  }

  public Ha getHa() {
    throw new ImplementMe();
  }

  public boolean isSetHa() {
    throw new ImplementMe();
  }

  public void setHa(Ha arg0) {
    throw new ImplementMe();
  }

  public void unsetHa() {
    throw new ImplementMe();
  }

  public UpdateCheck addNewUpdateCheck() {
    throw new ImplementMe();    
  }
  
  public UpdateCheck getUpdateCheck() {
    throw new ImplementMe();    
  }

  public boolean isSetUpdateCheck() {
    throw new ImplementMe();    
  }
  
  public void setUpdateCheck(UpdateCheck arg0) {
    throw new ImplementMe();    
  }

  public void unsetUpdateCheck() {
    throw new ImplementMe();    
  }
}
