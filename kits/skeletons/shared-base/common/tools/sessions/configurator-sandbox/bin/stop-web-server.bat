@echo off

rem
rem  All content copyright (c) 2003-2008 Terracotta, Inc.,
rem  except as may otherwise be noted in a separate copyright notice.
rem  All rights reserved.
rem

REM ------------------------------------------------------------
REM - stop-web-server.bat {tomcat5.0|tomcat5.5|wls8.1} 908{1,2}
REM ------------------------------------------------------------

setlocal
set EXIT_ON_ERROR=TRUE
call "%~d0%~p0..\%1\stop.bat" %2
exit %ERRORLEVEL%
endlocal
