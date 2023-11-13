package com.lambda;


import com.amazonaws.auth.policy.actions.Route53Actions;
import com.amazonaws.client.builder.AwsClientBuilder;
import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.AmazonEC2Client;
import com.amazonaws.services.ec2.model.DescribeNetworkInterfacesResult;
import com.amazonaws.services.ec2.model.NetworkInterface;
import com.amazonaws.services.ecs.AmazonECS;
import com.amazonaws.services.ecs.AmazonECSClient;
import com.amazonaws.services.ecs.model.Attachment;
import com.amazonaws.services.ecs.model.ListTagsForResourceRequest;
import com.amazonaws.services.ecs.model.ListTagsForResourceResult;
import com.amazonaws.services.ecs.model.Tag;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.ScheduledEvent;
import com.amazonaws.services.route53.AmazonRoute53;
import com.amazonaws.services.route53.AmazonRoute53Client;
import com.amazonaws.services.route53.model.*;
import com.amazonaws.util.StringUtils;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.util.JSONPObject;
import com.fasterxml.jackson.datatype.joda.JodaModule;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONObject;


import java.util.*;


// Handler value: com.lambda.HandlerCWEvents
public class HandlerCWEvents implements RequestHandler<ScheduledEvent, List<String>> {

    // Initialize the Log4j logger.
    static final Logger logger = LogManager.getLogger(HandlerCWEvents.class);

    AmazonECS ecs = AmazonECSClient.builder()
            .withRegion("us-east-1")
            .build();
    AmazonEC2 ec2 = AmazonEC2Client.builder()
            .withRegion("us-east-1")
            .build();

    AmazonRoute53 route53 = AmazonRoute53Client.builder()
            .withRegion("us-east-1")
            .build();

    @Override
    public List<String> handleRequest(ScheduledEvent event, Context context) {

/*        ObjectMapper mapper = new ObjectMapper().registerModule(new JodaModule());

        try {
            System.out.println("EVENT: " + mapper.writeValueAsString(event));
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }*/

        System.out.println("EVENT1: " + event.toString());

        String domain = null;
        List<String> services = new ArrayList<>();
        String hostedZoneId = null;


        String clusterArn = event.getDetail().get("clusterArn").toString();
        System.out.println("clusterArn: " + clusterArn);

        ListTagsForResourceResult result = ecs.listTagsForResource(new ListTagsForResourceRequest().withResourceArn(clusterArn));

        Optional<String> domainOpt = result.getTags().stream().filter(t -> t.getKey().equalsIgnoreCase("domain")).map(Tag::getValue).findFirst();
        if (domainOpt.isPresent()) {
            domain = domainOpt.get();
        }

        Optional<String> servicesOpt = result.getTags().stream().filter(t -> t.getKey().equalsIgnoreCase("services")).map(Tag::getValue).findFirst();
        if (servicesOpt.isPresent()) {
            services = Arrays.asList(servicesOpt.get().split("/"));
        }

        Optional<String> hostedZoneIdOpt = result.getTags().stream().filter(t -> t.getKey().equalsIgnoreCase("hostedZoneId")).map(Tag::getValue).findFirst();
        if (hostedZoneIdOpt.isPresent()) {
            hostedZoneId = hostedZoneIdOpt.get();
        }


        System.out.println("domain = " + domain + "  services = " + services + "  hostedZoneId = " + hostedZoneId);

        //get ENI
        String eniId = getEniId(event);
        System.out.println("EniId = " + eniId);
        if (StringUtils.isNullOrEmpty(eniId)) {
            logger.error("Network interface not found");
            return null;
        }

        String taskPublicIp = fetchEniPublicIp(eniId);
        String serviceName = fetchServiceName(event);
        System.out.println("Service name " + serviceName);

        if (!services.contains(serviceName)) {
            logger.error("This service is not in the trigger list.");
            return null;
        }

        assert domain != null;
        String containerDomain = serviceName.concat(".").concat(domain);

        updateDnsRecord(route53, hostedZoneId, containerDomain, taskPublicIp);

        logger.info("DNS record update finished for {} ({}})", containerDomain, taskPublicIp);


        return new ArrayList<String>(Collections.singleton("DNS record update finished for " + containerDomain + " publicIp = " + taskPublicIp));
    }


    private String getEniId(ScheduledEvent event) {

        System.out.println("Obj: " + event.getDetail());

        JSONObject jsonObject = new JSONObject(event.getDetail());
        JSONArray attachments = jsonObject.getJSONArray("attachments");

        for (Object obj : attachments) {
            JSONObject json = (JSONObject) obj;
            if (json.getString("type").equalsIgnoreCase("eni")) {
                JSONArray jsonArray = json.getJSONArray("details");
                for (Object o : jsonArray) {
                    JSONObject jo = (JSONObject) o;
                    if (jo.getString("name").equalsIgnoreCase("networkInterfaceId")) {
                        return jo.getString("value");
                    }
                }
            }
        }
        return null;
    }


    private String fetchEniPublicIp(String eniId) {
        DescribeNetworkInterfacesResult result = ec2.describeNetworkInterfaces().withNetworkInterfaces(new NetworkInterface().withNetworkInterfaceId(eniId));
        System.out.println("result = " + result);
        System.out.println("result2 = " + result.getNetworkInterfaces().get(0).getPrivateIpAddresses().get(0));
        return result.getNetworkInterfaces().get(0).getPrivateIpAddresses().get(0).getAssociation().getPublicIp();
    }


    private String fetchServiceName(ScheduledEvent event) {
        String group = event.getDetail().get("group").toString();
        return group.split(":")[1];
    }


    public void updateDnsRecord(AmazonRoute53 route53, String hostedZoneId, String domain, String publicIp) {

        ListHostedZonesResult hostedZonesResult = route53.listHostedZones();
        Optional<HostedZone> hostedZoneOpt = hostedZonesResult.getHostedZones().stream()
                .filter(zone -> hostedZoneId.equals(zone.getId())).findFirst();

        if (hostedZoneOpt.isPresent()) {
            ResourceRecordSet alias = new ResourceRecordSet(domain, "A");
            alias.setName(domain);
            alias.setTTL(180L);
            alias.setResourceRecords(Collections.singleton(new ResourceRecord().withValue(publicIp)));

            Change updateAlias = new Change(ChangeAction.UPSERT, alias);
            List<Change> changes = Collections.singletonList(updateAlias);
            ChangeBatch changeBatch = new ChangeBatch(changes);

            ChangeResourceRecordSetsRequest changeRecordRequest =
                    new ChangeResourceRecordSetsRequest(hostedZoneOpt.get().getId(), changeBatch);
            route53.changeResourceRecordSets(changeRecordRequest);

        } else {
            logger.error("No such hosted zone with id: {}", hostedZoneId);
        }

    }


}