package com.lambda;


import com.amazonaws.client.builder.AwsClientBuilder;
import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.AmazonEC2Client;
import com.amazonaws.services.ecs.AmazonECS;
import com.amazonaws.services.ecs.AmazonECSClient;
import com.amazonaws.services.ecs.model.ListTagsForResourceRequest;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.ScheduledEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;


import java.util.ArrayList;
import java.util.List;


// Handler value: com.lambda.HandlerCWEvents
public class HandlerCWEvents implements RequestHandler<ScheduledEvent, List<String>> {

    // Initialize the Log4j logger.
    static final Logger logger = LogManager.getLogger(HandlerCWEvents.class);


    @Override
    public List<String> handleRequest(ScheduledEvent event, Context context) {

        logger.info("EVENT: " + event.toString());
        logger.info("EVENT TYPE: " + event.getClass().toString());

        AmazonECS ecs = AmazonECSClient.builder()
                .withRegion("us-east-1")
                .build();
        AmazonEC2 ec2 = AmazonEC2Client.builder()
                .withRegion("us-east-1")
                .build();

        String clusterArn = event.getDetail().get("clusterArn").toString();
        System.out.println("clusterArn: " + clusterArn);


        logger.info(ec2.describeTags().toString());


        return new ArrayList<String>(event.getResources());
    }





/*    public String createRecordSet(String domain, String publicIp) {
        return {
                "Action": "UPSERT",
                "ResourceRecordSet": {
            "Name": domain,
                    "Type": "A",
                    "TTL": 180,
                    "ResourceRecords": [
            {
                "Value": publicIp
            }
            ]
        }
    }
    }*/


}