@echo off

set JAVA_OPTS=-Xms1G -Xmx1G

:: remote debug
:: set JAVA_OPTS=%JAVA_OPTS% -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=0.0.0.0:8000

:: flight recorder
:: set JAVA_OPTS=%JAVA_OPTS% -XX:+FlightRecorder -XX:StartFlightRecording=duration=200s,filename=flight.jfr

:: activate HotswapAgent when using JBR
:: https://github.com/JetBrains/JetBrainsRuntime/releases
:: set JAVA_OPTS=%JAVA_OPTS% -XX:HotswapAgent=fatjar

:: use mvn for running application without building it
mvn spring-boot:run -Dspring-boot.run.jvmArguments="%JAVA_OPTS%" -Dlicense.skip=true