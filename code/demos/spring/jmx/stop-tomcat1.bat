@echo off

rem
rem  All content copyright (c) 2003-2006 Terracotta, Inc.,
rem  except as may otherwise be noted in a separate copyright notice.
rem  All rights reserved.
rem

setlocal
cd %~d0%~p0
set TC_INSTALL_DIR=..\..\..
for %%i in ("%TC_INSTALL_DIR%") do set TC_INSTALL_DIR=%%~fsi

set CATALINA_HOME=%TC_INSTALL_DIR%\vendors\tomcat5.5

if not exist "%JAVA_HOME%" set JAVA_HOME=%TC_INSTALL_DIR%\jre
for %%i in ("%JAVA_HOME%") do set JAVA_HOME=%%~fsi

set CATALINA_BASE=tomcat1
echo "stopping terracotta for spring: jmx sample: tomcat server node 1" 
%CATALINA_HOME%\bin\catalina.bat stop
endlocal
