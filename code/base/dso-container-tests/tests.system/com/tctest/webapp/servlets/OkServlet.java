/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright notice.  All rights reserved.
 */
package com.tctest.webapp.servlets;

import java.io.IOException;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

public final class OkServlet extends HttpServlet {

  public void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
    HttpSession session = request.getSession(true);
    response.setContentType("text/html");
    String cmd = request.getParameter("cmd");
    
    if (cmd != null && cmd.equals("getMaxInactiveInterval")) {
      response.getWriter().println(session.getMaxInactiveInterval());
    } else {
      session.setAttribute("da", "bomb");    
      response.getWriter().println("OK");
    }
    
  }
}