# The monitor uses no reflection, so consumers need no extra keep rules.
# Keep the public entry points readable in stack traces of shrunk debug builds.
-keepnames class gg.padu.httpmonitor.HttpMonitor
