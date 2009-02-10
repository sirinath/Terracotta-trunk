#!/bin/sh

#
# All content copyright Terracotta, Inc., unless otherwise indicated. All rights reserved.


#

# -------------------
# stop.cmd 908{1,2}
# -------------------

cd "`dirname $0`/.."
SANDBOX="`pwd`"
TC_INSTALL_DIR="${SANDBOX}/../../.."

WL_HOME="${BEA_HOME}/weblogic81"
PRODUCTION_MODE=
JAVA_VENDOR="Sun"
export PRODUCTION_MODE JAVA_VENDOR

ADMIN_URL="t3://localhost:$1"
SERVER_NAME="myserver"

. "${WL_HOME}/common/bin/commEnv.sh"

CLASSPATH="${WEBLOGIC_CLASSPATH}:${POINTBASE_CLASSPATH}:${JAVA_HOME}/jre/lib/rt.jar:${WL_HOME}/server/lib/webservices.jar:${CLASSPATH}"

exec "${JAVA_HOME}/bin/java" -classpath "${CLASSPATH}" weblogic.Admin FORCESHUTDOWN -url "${ADMIN_URL}" -username tc -password tc "${SERVER_NAME}"
