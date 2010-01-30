#!/bin/bash

cygwin=false
if [ `uname | grep CYGWIN` ]; then
  cygwin=true
fi

if [ "$JAVA_HOME" = "" ]; then
  echo "JAVA_HOME is not defined"
  exit 1
fi

root=`dirname $0`
tc_install_dir=../../../

cd $root

mkdir -p classes

ehcache_core=`\ls -1   ../../../ehcache/ehcache-core-*.jar | tail -1`
if [ ! -f $ehcache_core ]; then
  echo "Couldn't find ehcache-core jar. Do you have a full kit?"
  exit 1
fi
classpath=$tc_install_dir/lib/servlet-api-2.5-6.1.8.jar:$ehcache_core
if $cygwin; then
  classpath=`cygpath -w -p $classpath`
fi

$JAVA_HOME/bin/javac -d classes -sourcepath src -cp $classpath src/demo/townsend/service/*.java
if [ $? -ne 0 ]; then 
  echo "Failed to compile demo. Do you have a full kit with Ehcache core?"
  exit 1
fi

mkdir -p dist
rm -rf dist/*
cp -r web/* dist
cp -r classes dist/WEB-INF
cp images/* dist
mkdir -p dist/WEB-INF/lib

#packaging echcache-core
cp $tc_install_dir/ehcache/ehcache-core*.jar dist/WEB-INF/lib
if [ $? -ne 0 ]; then
  echo "Couldn't package ehcache-core. Do you have a complete kit?"
  exit 1
fi

#packaging ehcache-terracotta
cp $tc_install_dir/ehcache/ehcache-terracotta*.jar dist/WEB-INF/lib
if [ $? -ne 0 ]; then
  echo "Couldn't package ehcache-terracotta. Do you have a complete kit?"
  exit 1
fi

#packaging terracotta-session
cp $tc_install_dir/sessions/terracotta-session*.jar dist/WEB-INF/lib
if [ $? -ne 0 ]; then
  echo "Couldn't package terracotta-session. Do you have a complete kit?"
  exit 1
fi

#create WAR
warname=Townsend.war
cd dist
$JAVA_HOME/bin/jar cf $warname *
if [ $? -eq 0 ]; then
  echo "$warname has been created successfully."
  exit 0
else
  echo "Error packaging $warname"
  exit 1
fi
