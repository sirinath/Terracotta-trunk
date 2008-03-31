@echo off

rem
rem  All content copyright (c) 2003-2008 Terracotta, Inc.,
rem  except as may otherwise be noted in a separate copyright notice.
rem  All rights reserved.
rem

rem
rem samples\spring\events
rem
rem Environment variables required by dso-env helper script:
rem  JAVA_HOME: root of Java Development Kit installation
rem  TC_INSTALL_DIR: root of Terracotta installation
rem
rem Arguments to dso-env helper script:
rem  -q: do not print value of TC_JAVA_OPTS
rem  TC_CONFIG_PATH: location of Terracotta config file; overridden by value
rem                  of optional TC_CONFIG
rem
rem Environment variable set by dso-env helper script:
rem  TC_JAVA_OPTS: Java options needed to activate DSO
rem

setlocal
cd %~d0%~p0
set TC_INSTALL_DIR=..\..\..
set TC_INSTALL_DIR="%TC_INSTALL_DIR:"=%"

set CATALINA_HOME=%TC_INSTALL_DIR%\vendors\tomcat5.5

if not defined JAVA_HOME set JAVA_HOME="%TC_INSTALL_DIR%\jre"
set JAVA_HOME="%JAVA_HOME:"=%"

set TC_CONFIG_PATH=tc-config.xml
call %TC_INSTALL_DIR%\bin\dso-env.bat -q --config "%TC_CONFIG%"
set JAVA_OPTS=%TC_JAVA_OPTS% -Dcom.sun.management.jmxremote %JAVA_OPTS%
set CATALINA_BASE=tomcat2
start "terracotta for spring: event sample: 8082" %CATALINA_HOME%\bin\catalina.bat run
endlocal
