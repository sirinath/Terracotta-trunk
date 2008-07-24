#!/bin/sh

#
#  All content copyright (c) 2003-2008 Terracotta, Inc.,
#  except as may otherwise be noted in a separate copyright notice.
#  All rights reserved.
#

# ----------------------------------------
# - start.sh 908{1,2} [nodso] [nowindow]
# ----------------------------------------

cd "`dirname $0`/.."
SANDBOX="`pwd`"
TC_INSTALL_DIR="${SANDBOX}/../../.."

if test -z "${JAVA_HOME}" -a -n "${BEA_HOME}" -a -d "${BEA_HOME}/jdk150_10"; then
  JAVA_HOME="${BEA_HOME}/jdk150_10"
  export JAVA_HOME
fi

if test -z "${JAVA_HOME}"; then
  echo "JAVA_HOME must be set to a 1.5 JDK."
  exit 1
fi

"$JAVA_HOME/bin/java" -classpath "$TC_INSTALL_DIR/lib/tc.jar" org.terracotta.ui.session.CheckJavaVersion "1.5"
if test "$?" != "0"; then
  echo Weblogic Server 9.2 requires Java 1.5. Exiting.
  exit 1
fi

PORT="$1"

if test "$2" != "nodso"; then
  TC_CONFIG_PATH="${SANDBOX}/wls9.2/tc-config.xml"
  set -- -q "${TC_CONFIG_PATH}"
  . "${TC_INSTALL_DIR}/bin/dso-env.sh"

  OPTS="${TC_JAVA_OPTS} -Dwebserver.log.name=weblogic-${PORT}"
  OPTS="${OPTS} -Dcom.sun.management.jmxremote"
  OPTS="${OPTS} -Dproject.name=Configurator"
  JAVA_OPTIONS="${OPTS} ${JAVA_OPTS}"
  export JAVA_OPTIONS
fi

cd "${SANDBOX}/wls9.2/${PORT}"
exec ../startWLS.sh
