@echo off

if not defined JAVA_HOME (
  echo JAVA_HOME is not defined
  exit /b 1
)

setlocal

set JAVA_HOME="%JAVA_HOME:"=%"
set root=%~d0%~p0..
set root="%root:"=%"
set jetty1=%root%\jetty6.1\9081\webapps
set jetty2=%root%\jetty6.1\9082\webapps
cd %root%
set tc_install_dir=..\..\..
rmdir /q /s dist
mkdir dist
xcopy /e /y /q web dist 1> NUL
mkdir dist\WEB-INF\classes 2> NUL
xcopy /e /y /q classes dist\WEB-INF\classes 1> NUL
mkdir dist\WEB-INF\lib 2> NUL

rem packaging terracotta-session
xcopy /y /q %tc_install_dir%\sessions\terracotta-session*.jar dist\WEB-INF\lib 1> NUL
if not %errorlevel% == 0  (
  echo "Couldn't package terracotta-session. Do you have a complete kit?"
  exit /b 1
)

rem create WAR
set warname=DepartmentTaskList.war
cd dist
%JAVA_HOME%\bin\jar cf %warname% *
if %errorlevel% == 0 (
  echo "%warname% has been created successfully. Deploying..."
  xcopy /y /q %warname% %jetty1% 1> NUL
  xcopy /y /q %warname% %jetty2% 1> NUL
  echo "Done."
) else (
  echo "Error packaging %warname%"
)

endlocal
