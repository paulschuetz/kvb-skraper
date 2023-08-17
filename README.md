# kvb-skraper
The contained program skrapes for connection data about the public transport in Cologne. This service is currently used to serve a mobile app.
The artifact is currently served via AWS Lambda which runs the program in a JVM. The goal is to deploy this as a native binary to the AWS Lambda runtime to reduce startup timem, which is why GraalVM is already on the classpath.
To test the native example, follow the instructions.

## Prerequisites

- Java 17
- GraalVM

## Compile & Run as Native Executable

```
$ ./gradlew clean nativeCompile
# this will take a while and should finally create a suitable executable for your machine under build/native/nativeCompile/kvb-skraper
$ build/native/nativeCompile/kvb-skraper
# this should execute the generated native program and print a json response to your console
```

