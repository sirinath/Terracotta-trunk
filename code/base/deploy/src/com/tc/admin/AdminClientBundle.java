/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.admin;

import java.util.ListResourceBundle;

public class AdminClientBundle extends ListResourceBundle {
  public Object[][] getContents() {
    return new Object[][] {
        { "cluster.node.label", "Terracotta cluster" },
        { "connect.label", "Connect" },
        { "disconnect.label", "Disconnect" },
        { "shutdown.label", "Shutdown" },
        { "shutdown.server.confirm", "Are you sure you want to shutdown {0}?" },
        { "stats.recorder.node.label", "Cluster statistics recorder" },
        { "stats.recording.suffix", " (on)" },
        { "quit.anyway", "Quit anyway?" },
        { "disconnect.anyway", "Disconnect anyway?" },
        { "recording.stats.msg", "There is an active statistic recording session.  {0}" },
        { "profiling.locks.msg", "Lock profiling is currently enabled.  {0}" },
        { "recording.stats.profiling.locks.msg",
            "<html>There is an active statistic recording session<br>and lock profiling is currently enabled.  {0}</html>" },
        { "sessions", "Sessions" },
        { "title", "Terracotta Administrator Console" },
        { "new.server.action.label", "New server" },
        { "new.cluster.action.label", "New cluster" },
        { "quit.action.label", "Quit" },
        { "connect.title", "Connect to JMX Server" },
        { "connecting.to", "Connecting to {0} ..." },
        { "connected.to", "Connected to {0}" },
        { "cannot.connect.to", "Unable to connect to {0}" },
        { "cannot.connect.to.extended", "Unable to connect to {0}: {1}" },
        { "service.unavailable", "Service Unavailable: {0}" },
        { "unknown.host", "Unknown host: {0}" },
        { "disconnecting.from", "Disconnecting from {0} ..." },
        { "disconnected.from", "Disconnected from {0}" },
        { "deleted.server", "Deleted {0}" },
        { "server.properties.headings", new String[] { "Name", "Value" } },
        { "server.activated.status", "{0} activated on {1}" },
        { "server.activated.label", "Activated on {0}" },
        { "server.started.status", "Started {0} on {1}" },
        { "server.started.label", "Started on {0}" },
        { "server.initializing.status", "Initializing {0} on {1}" },
        { "server.initializing.label", "Initializing on {0}" },
        { "server.standingby.status", "{0} standing by on {1}" },
        { "server.standingby.label", "Standing by on {0}" },
        { "server.disconnected.status", "{0} disconnected on {1}" },
        { "server.disconnected.label", "Disconnected on {0}" },
        {
            "server.non-restartable.warning",
            "<html>This server is configured for <code>temporary-swap-only</code> persistence mode and so will not "
                + "allow clients to reconnect upon restart.  To allow for client reconnect upon restart, change the "
                + "server's configured persistence mode to <code>permanent-store</code> and restart:</html>" },
        { "dso", "DSO" },
        { "dso.roots", "Cluster object browser" },
        { "dso.client.roots", "Client object browser" },
        { "dso.locks", "Lock profiler" },
        { "dso.locks.profiling.suffix", " (on)" },
        {
            "dso.locks.column.headings",
            new String[] { "Lock", "<html>Times<br>Requested</html>", "<html>Times<br>Hopped</html>",
                "<html>Average<br>Contenders</html>", "<html>Average<br>Acquire Time</html>",
                "<html>Average<br>Held Time</html>" } },
        {
            "dso.locks.column.tips",
            new String[] {
                "Lock identifier",
                "<html>Number of times this lock<br>was requested</html>",
                "<html>Times an acquired greedy lock was<br>retracted from holding client and<br>granted to another</html>",
                "<html>Average number of threads wishing<br>to acquire this lock at the time<br>it was requested</html>",
                "<html>Average time between lock<br>request and grant</html>",
                "<html>Average time grantee held<br>this lock</html>",
                "<html>Average number of outstanding<br>locks held by acquiring thread<br>at grant time</html>" } },
        { "refresh.name", "Refresh" },
        { "dso.roots.refreshing", "Refreshing roots..." },
        { "dso.deadlocks.detect", "Detect deadlocks" },
        { "dso.deadlocks.detecting", "Detecting deadlocks..." },
        { "dso.classes", "Classes" },
        { "dso.allClasses", "All classes" },
        { "dso.classes.refreshing", "Refreshing classes..." },
        { "dso.classes.className", "Class" },
        { "dso.classes.instanceCount", "Creation count since active server start" },
        { "dso.classes.config.desc",
            "This config snippet is constructed from the set of shared instances created since the server started." },
        { "dso.locks.refreshing", "Refreshing locks..." },
        { "dso.object.flush.rate", "Object Flush Rate" },
        { "dso.object.fault.rate", "Object Fault Rate" },
        { "dso.transaction.rate", "Transaction Rate" },
        { "dso.pending.client.transactions", "Unacknowledged Transaction Broadcasts" },
        { "dso.root.retrieving", "Retrieving new DSO root..." },
        { "dso.root.new", "Added new DSO root: " },
        { "cluster.thread.dumps", "Cluster thread dumps" },
        { "server.thread.dumps", "Server thread dumps" },
        { "client.thread.dumps", "Client thread dumps" },
        { "clients", "Clients" },
        { "servers", "Servers" },
        { "dso.client.retrieving", "Retrieving new DSO client..." },
        { "dso.client.new", "Added new DSO client: " },
        { "dso.client.detaching", "Detaching DSO client..." },
        { "dso.client.detached", "Detached DSO client: " },
        { "dso.client.host", "Host" },
        { "dso.client.port", "Port" },
        { "dso.client.channelID", "ChannelID" },
        { "dso.client.liveObjectCount", "Live objects" },
        { "liveObjectCount.tip", "<html>Number of managed objects currently resident,<br>excluding literals</html>" },
        { "dso.gcstats", "Distributed garbage collection" },
        { "map.entry", "MapEntry" },
        { "log.error", "ERROR" },
        { "log.warn", "WARN" },
        { "log.info", "INFO" },
        { "dso.cache.rate.domain.label", "Time" },
        { "dso.cache.rate.range.label", "Objects per second" },
        { "dso.transaction.rate.range.label", "Transactions per second" },
        { "dso.cache.activity", "Cache activity" },
        { "dso.cache.miss.rate", "Cache Miss Rate" },
        { "dso.cache.miss.rate.label", "Cache Misses per second" },
        { "dso.gcstats.iteration", "Iteration" },
        { "dso.gcstats.type", "Type" },
        { "dso.gcstats.status", "Status" },
        { "dso.gcstats.startTime", "<html>Start<br>time</html>" },
        { "dso.gcstats.elapsedTime", "<html>Total<br>elapsed<br>time (ms.)</html>" },
        { "dso.gcstats.beginObjectCount", "<html>Begin<br>count</html>" },
        { "dso.gcstats.pausedStageTime", "<html>Paused<br>stage (ms.)</html>" },
        { "dso.gcstats.markStageTime", "<html>Mark<br>stage (ms.)</html>" },
        { "dso.gcstats.actualGarbageCount", "<html>Garbage<br>count</html>" },
        { "dso.gcstats.deleteStageTime", "<html>Delete<br>stage (ms.)</html>" },
        { "dso.all.statistics", "All statistics" },
        { "file.menu.label", "File" },
        { "help.menu.label", "Help" },
        { "help.item.label", "Terracotta Console Help..." },
        { "about.action.label", "About Terracotta Console" },
        { "update-checker.control.label", "Check for updates" },
        { "update-checker.action.label", "Update Checker..." },
        { "update-checker.connect.failed.msg", "Unable to connect to update site." },
        { "update-checker.current.msg", "Your software is up-to-date." },
        { "update-checker.updates.available.msg", "New Terracotta versions are now available." },
        { "update-checker.release-notes.label", "Release notes" },
        { "update-checker.action.title", "Terracotta Update Checker" },
        { "update-checker.last.checked.msg", "Last checked: {0}" },
        { "version.check.enable.label", "Check Server Version" },
        { "version.check.disable.label", "Disable version checks" },
        {
            "version.check.message",
            "<html><h3>Version mismatch for {0}.</h3><br>"
                + "<table border=0 cellspacing=1><tr><td align=right><b>Terracotta Server Version:</b></td><td>{1}"
                + "</tr><tr><td align=right><b>AdminConsole Version:</b</td><td>{2}"
                + "</td></tr></table><h3>Continue?</h3></html>" }, { "cpu.usage", "Host CPU" },
        { "object.flush.rate", "Object Flush Rate" }, { "object.fault.rate", "Object FaultRate" },
        { "transaction.rate", "Transaction Rate" }, { "cache.miss.rate", "Cache Miss Rate" },
        { "heap.usage", "Heap Usage" }, { "heap.usage.max", "memory max" }, { "heap.usage.used", "memory used" },
        { "pending.transactions", "Unacknowledged Transaction Broadcasts" }, };
  }
}
