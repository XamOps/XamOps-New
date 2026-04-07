package com.xammer.cloud.dto.isv;

import lombok.Data;

/**
 * Payload received when an AWS Marketplace buyer completes the subscription flow.
 * AWS redirects the buyer to the ISV's fulfillment URL with the registration token
 * as a query parameter: ?x-amzn-marketplace-token=TOKEN
 *
 * The ISV's fulfillment URL should be configured to point to XamOps:
 *   https://app.xamops.io/marketplace/register
 *
 * XamOps then calls ResolveCustomer(token) to validate and register the customer.
 */
@Data
public class CustomerRegistrationRequest {

    /** x-amzn-marketplace-token from the AWS Marketplace redirect */
    private String registrationToken;

    /** Product code — identifies which ISV product this registration belongs to */
    private String productCode;
}
