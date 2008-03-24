@echo off

rem
rem  All content copyright (c) 2003-2008 Terracotta, Inc.,
rem  except as may otherwise be noted in a separate copyright notice.
rem  All rights reserved.
rem

rem -------------------------------------
rem - start.bat 908{1,2} [nodso]
rem -------------------------------------

setlocal
cd %~d0%~p0..
set SANDBOX=%CD%
for %%i in ("%SANDBOX%") do set SANDBOX=%%~fsi

set TC_INSTALL_DIR=%SANDBOX%\..\..\..

if not defined JAVA_HOME set JAVA_HOME="%TC_INSTALL_DIR%\jre"
set JAVA_HOME="%JAVA_HOME:"=%"
if not exist %JAVA_HOME% set JAVA_HOME=%TC_INSTALL_DIR%\jre
for %%i IN (%JAVA_HOME%) do set JAVA_HOME=%%~fsi

if not defined CATALINA_HOME (
  echo CATALINA_HOME must be set to a Tomcat6.0 installation.
  exit 1
) else (
  set CATALINA_HOME="%CATALINA_HOME:"=%"
  for %%i in (%CATALINA_HOME%) do set CATALINA_HOME=%%~fsi
  if not exist %CATALINA_HOME% (
    echo CATALINA_HOME %CATALINA_HOME% does not exist.
    exit 1
  )
)
set CATALINA_BASE=%SANDBOX%\tomcat6.0\%1

rem --------------------------------------------------------------------
rem - The Configurator passes 'nodso' as the second argument to this
rem - script if you've disabled DSO in its GUI...
rem --------------------------------------------------------------------

if "%2" == "nodso" goto runCatalina

set TC_CONFIG_PATH=%SANDBOX%\tomcat6.0\tc-config.xml
call %TC_INSTALL_DIR%\bin\dso-env.bat -q "%TC_CONFIG%"

if "%EXITFLAG%"=="TRUE" goto end

set JAVA_OPTS=%JAVA_OPTS% %TC_JAVA_OPTS%
set JAVA_OPTS=%JAVA_OPTS% -Dwebserver.log.name=%1
set JAVA_OPTS=%JAVA_OPTS% -Dcom.sun.management.jmxremote
set OPTS=%OPTS% -Dproject.name=Configurator
set JAVA_OPTS=%JAVA_OPTS% -Dtc.node-name=tomcat-%1

:runCatalina

cd %SANDBOX%
call %CATALINA_HOME%\bin\catalina.bat run

:end
exit %ERRORLEVEL%
endlocal
