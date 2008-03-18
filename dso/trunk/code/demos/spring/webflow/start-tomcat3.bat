@echo off

rem
rem  All content copyright (c) 2003-2008 Terracotta, Inc.,
rem  except as may otherwise be noted in a separate copyright notice.
rem  All rights reserved.
rem

rem
rem samples\spring\webflow
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
for %%i in ("%TC_INSTALL_DIR%") do set TC_INSTALL_DIR=%%~fsi

set CATALINA_HOME=%TC_INSTALL_DIR%\vendors\tomcat5.5

if not defined JAVA_HOME set JAVA_HOME="%TC_INSTALL_DIR%\jre"
set JAVA_HOME="%JAVA_HOME:"=%"
if not exist %JAVA_HOME% set JAVA_HOME=%TC_INSTALL_DIR%\jre
FOR %%i IN (%JAVA_HOME%) DO SET JAVA_HOME=%%~fsi

set TC_CONFIG_PATH=tc-config.xml
call %TC_INSTALL_DIR%\bin\dso-env.bat -q "%TC_CONFIG%"
set OPTS=%TC_JAVA_OPTS% -Dcom.sun.management.jmxremote
set OPTS=%OPTS% -Dcom.sun.management.jmxremote.port=8093
set OPTS=%OPTS% -Dcom.sun.management.jmxremote.authenticate=false
set OPTS=%OPTS% -Dcom.sun.management.jmxremote.ssl=false
set OPTS=%OPTS% -Dtc.node-name=Node3
set JAVA_OPTS=%OPTS% %JAVA_OPTS%
set CATALINA_BASE=tomcat3
start "terracotta for spring: webflow sample: 8083" %CATALINA_HOME%\bin\catalina.bat run
endlocal
