@echo off

rem
rem  All content copyright (c) 2003-2006 Terracotta, Inc.,
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

if "%JAVA_HOME%" == "" (
  set JAVA_HOME=%BEA_HOME%\jdk150_10
)

IF NOT EXIST "%JAVA_HOME%" (
  echo JAVA_HOME '%JAVA_HOME%' does not exist.
  exit 1
  endlocal  
)

for %%i in ("%JAVA_HOME%") do set JAVA_HOME=%%~fsi

%JAVA_HOME%\bin\java -classpath %TC_INSTALL_DIR%\lib\tc.jar com.tc.CheckJavaVersion "1.5"
if %ERRORLEVEL% NEQ 0 (
  echo Weblogic Server 9.2 requires Java 1.5. Exiting.
  goto end
)

if ""%2"" == ""nodso"" goto doRunWLS

set TC_CONFIG_PATH=%SANDBOX%\wls9.2\tc-config.xml
call %TC_INSTALL_DIR%\bin\dso-env.bat -q "%TC_CONFIG%"

if %ERRORLEVEL% neq 0 goto end

set OPTS=%TC_JAVA_OPTS% -Dwebserver.log.name=%1
set OPTS=%OPTS% -Dcom.sun.management.jmxremote
set OPTS=%OPTS% -Dtc.node-name=weblogic-%1
set OPTS=%OPTS% -Dproject.name=Configurator
set JAVA_OPTIONS=%OPTS% %JAVA_OPTS%

:doRunWLS
cd %~d0%~p0%1
call ..\startWLS.bat

:end
exit %ERRORLEVEL%
endlocal
