#! /bin/bash

# exit when any command fails
set -e
# exit when script tries to use undeclared variables
set -u
# prevents errors in a pipeline from being masked
set -o pipefail

# go to working dir
cd "/home/paul/workbench/projects/other/kvb-light/kvb-data-extractor"

export AWS_PROFILE="kvb-light-operator"

./gradlew shadowJar

aws s3 mv ./build/libs/kvb-skraper-1.0-SNAPSHOT-all.jar s3://kvb-light/kvb-data-extractor.jar

aws lambda update-function-code --function-name kvb-fetcher \
 --s3-bucket kvb-light --s3-key kvb-data-extractor.jar --no-cli-pager
