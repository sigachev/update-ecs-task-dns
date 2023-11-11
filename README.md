# Automatic publishing of DNS entry for Fargate-managed containers in Amazon ECS
Java implementation of AWS Lambda function that updates DNS entry on CloudWatch event (state of ECS task changes to "running")

This way we always have a DNS A record that point to ECS service running task.

Fargate-managed containers in ECS lack build-in support for registering services into public DNS namespaces (11/2023).
This is an event-driven approach to automatically register the public IP of a deployed container in a Route 53 hosted zone.


## How it works

A lambda function subscribes to an "ECS Task State Change" event.
It gets called whenever a container has started up. What the function does is :

* fetching the public IP from the container
* construct a subdomain for the container
* register the public IP for the subdomain in Route 53

## Installation


You need to have the *Serverless Framework* CLI installed.

Deploy the function in your active AWS account:

```
serverless deploy
```

Or you can manually archive this project and upload it to your AWS account.


In your ECS console, select your cluster and add the tags

* hostedZoneId (the hosted zone id of your public DNS namespace, for example `Z03683693E1POL4P4T9EW`)
* domain (the domain name of your public DNS namespace, for example `finmates.com`)
* services (service names separated by "/" DNS entries should be updated for, for example `stocks/notifications`)

## Demo

Well, just start a Fargate task in your cluster. When the task has started up, the function creates an A-record-set in your
hosted zone with the containers' service name as subdomain.

## Notes
To create an event you need to go to EventBridge Console and do it from there.

### Event pattern:

    {
      "source": ["aws.ecs"],
         "detail-type": ["ECS Task State Change"],
         "detail": {
            "desiredStatus": ["RUNNING"],
            "lastStatus": ["RUNNING"]
         }
    }

Lambda handler: src/update-task-dns.handler
