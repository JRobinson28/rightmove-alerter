# Rightmove Alerter

![Tests](https://github.com/JRobinson28/rightmove-alerter/actions/workflows/test.yml/badge.svg) ![Build](https://github.com/JRobinson28/rightmove-alerter/actions/workflows/deploy.yml/badge.svg)

Creating custom rightmove rental alerts using AWS Lambda.

## How it works

The `rightmove-alerter` Lambda function runs on a schedule (default 4 minutes). When provided with a map of Rightmove rental search criteria, it scrapes the search results page using [Enlive](https://github.com/cgrand/enlive) and extracts the URLS of any properties found. It then checks if any don't already exist in the `latest-homes` DynamoDB table, in which case it uploads them and creates an email message containing the new URLS. This is sent to the `latest-homes` SNS topic which is subscribed to by the user's email address.

The application and AWS infrastructure is deployed using the [AWS Serverless Application Model](https://aws.amazon.com/serverless/sam/) framework. The lambda function is deployed to a Docker container, following [this example](https://github.com/wtfleming/clojure-aws-lambda-example).

## Architecture

<p align="center">
  <img src="./resources/architecture-diagram.png">
</p>
