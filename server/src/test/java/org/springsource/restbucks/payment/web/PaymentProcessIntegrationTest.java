/*
 * Copyright 2012-2021 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springsource.restbucks.payment.web;

import static org.assertj.core.api.Assertions.*;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import lombok.extern.slf4j.Slf4j;
import net.minidev.json.JSONArray;
import net.minidev.json.JSONObject;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.hateoas.IanaLinkRelations;
import org.springframework.hateoas.Link;
import org.springframework.hateoas.LinkRelation;
import org.springframework.hateoas.MediaTypes;
import org.springframework.hateoas.mediatype.hal.HalLinkRelation;
import org.springframework.hateoas.mediatype.hal.HalLinkRelation.HalLinkRelationBuilder;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springsource.restbucks.AbstractWebIntegrationTest;
import org.springsource.restbucks.Restbucks;
import org.springsource.restbucks.order.Order;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;

/**
 * Integration tests modeling the hypermedia-driven interaction flow against the server implementation. Uses the Spring
 * MVC integration test facilities introduced in 3.2. Implements the order process modeled in my presentation on
 * Hypermedia design with Spring.
 *
 * @see http://bit.ly/UIcDvq
 * @author Oliver Gierke
 */
@Slf4j
class PaymentProcessIntegrationTest extends AbstractWebIntegrationTest {

	private static final HalLinkRelationBuilder BUILDER = HalLinkRelation.curieBuilder(Restbucks.CURIE_NAMESPACE);

	private static final LinkRelation ORDERS_REL = BUILDER.relation("orders");
	private static final LinkRelation ORDER_REL = BUILDER.relation("order");
	private static final LinkRelation RECEIPT_REL = BUILDER.relation("receipt");
	private static final LinkRelation CANCEL_REL = BUILDER.relation("cancel");
	private static final LinkRelation UPDATE_REL = BUILDER.relation("update");
	private static final LinkRelation PAYMENT_REL = BUILDER.relation("payment");

	private static final String FIRST_ORDER_EXPRESSION = String
			.format("$._embedded.%s[?(@.status == 'Payment expected')]", ORDERS_REL);

	/**
	 * Processes the first existing {@link Order} found.
	 */
	@Test
	void processExistingOrder() throws Exception {

		var response = accessRootResource();

		response = discoverOrdersResource(response);
		response = accessFirstOrder(response);
		response = triggerPayment(response);
		response = pollUntilOrderHasReceiptLink(response);
		response = takeReceipt(response);

		verifyOrderTaken(response);
	}

	/**
	 * Creates a new {@link Order} and processes that one.
	 */
	@Test
	void processNewOrder() throws Exception {

		var response = accessRootResource();

		response = createNewOrder(response);
		response = triggerPayment(response);
		response = pollUntilOrderHasReceiptLink(response);
		response = takeReceipt(response);

		verifyOrderTaken(response);
	}

	/**
	 * Creates a new {@link Order} and cancels it right away.
	 */
	@Test
	void cancelOrderBeforePayment() throws Exception {

		var response = accessRootResource();

		response = createNewOrder(response);
		cancelOrder(response);
	}

	/**
	 * Access the root resource by referencing the well-known URI. Verifies the orders resource being present.
	 *
	 * @return the response for the orders resource
	 * @throws Exception
	 */
	private MockHttpServletResponse accessRootResource() throws Exception {

		LOG.info("Accessing root resource…");

		return mvc.perform(get("/") //
				.accept(MediaTypes.HAL_FORMS_JSON)) //
				.andExpect(status().isOk()) //
				.andExpect(linkWithRelIsPresent(ORDERS_REL)) //
				.andReturn().getResponse();
	}

	/**
	 * Creates a new {@link Order} by looking up the orders link from the source and posting the content of
	 * {@code orders.json} to it. Verifies we receive a {@code 201 Created} and a {@code Location} header. Follows the
	 * location header to retrieve and verify the {@link Order} just created.
	 *
	 * @param source
	 * @return
	 * @throws Exception
	 */
	private MockHttpServletResponse createNewOrder(MockHttpServletResponse source) throws Exception {

		String content = source.getContentAsString();

		var parse = JsonPath.parse(content);

		// Find drink to add place the order
		var drinksTemplate = parse.read("$._templates.default.properties[0].options.link.href", String.class);
		var drinksOptionsUri = Link.of(drinksTemplate).expand().getHref();
		var drinksOptionsResponse = mvc.perform(get(drinksOptionsUri))
				.andReturn().getResponse().getContentAsString();
		var drinkUri = JsonPath.parse(drinksOptionsResponse).read("$[0].value", String.class);

		// Select location
		var location = parse.read("$._templates.default.properties[1].options.inline[0]");

		var payload = Map.of("drinks", List.of(drinkUri), "location", location);

		var ordersLink = getDiscovererFor(source).findRequiredLinkWithRel(ORDERS_REL, content);

		var result = mvc.perform(post(ordersLink.expand().getHref()) //
				.contentType(MediaType.APPLICATION_JSON)
				.content(new ObjectMapper().writeValueAsString(payload))) //
				.andExpect(status().isCreated()) //
				.andExpect(header().string("Location", is(notNullValue()))) //
				.andReturn().getResponse();

		return mvc.perform(get(result.getHeader("Location"))).andReturn().getResponse();
	}

	/**
	 * Follows the {@code orders} link returns the {@link Order}s found.
	 *
	 * @param source
	 * @return
	 * @throws Exception
	 */
	private MockHttpServletResponse discoverOrdersResource(MockHttpServletResponse source) throws Exception {

		var content = source.getContentAsString();
		var ordersLink = getDiscovererFor(source).findRequiredLinkWithRel(ORDERS_REL, content);

		LOG.info("Root resource returned: " + content);
		LOG.info(String.format("Found orders link pointing to %s… Following…", ordersLink));

		var response = mvc.perform(get(ordersLink.expand().getHref())). //
				andExpect(status().isOk()). //
				andReturn().getResponse();

		LOG.info("Found orders: " + response.getContentAsString());
		return response;
	}

	/**
	 * Looks up the first {@link Order} from the orders representation using a JSONPath expression of
	 * {@value #FIRST_ORDER_EXPRESSION}. Looks up the {@value Link#REL_SELF} link from the nested object and follows it to
	 * lookup the representation. Verifies the {@code self}, {@code cancel}, and {@code update} link to be present.
	 *
	 * @param source
	 * @return
	 * @throws Exception
	 */
	private MockHttpServletResponse accessFirstOrder(MockHttpServletResponse source) throws Exception {

		var content = source.getContentAsString();

		var orders = JsonPath.parse(content)
				.read(FIRST_ORDER_EXPRESSION, JSONArray.class)
				.toJSONString();

		var order = JsonPath.parse(orders)
				.read("$.[0]", JSONObject.class)
				.toJSONString();

		var orderLink = getDiscovererFor(source)
				.findRequiredLinkWithRel(IanaLinkRelations.SELF, order)
				.expand();

		LOG.info(String.format("Picking first order using JSONPath expression %s…", FIRST_ORDER_EXPRESSION));
		LOG.info(String.format("Discovered self link pointing to %s… Following", orderLink));

		return mvc.perform(get(orderLink.getHref())). //
				andExpect(linkWithRelIsPresent(IanaLinkRelations.SELF)). //
				andExpect(linkWithRelIsPresent(CANCEL_REL)). //
				andExpect(linkWithRelIsPresent(UPDATE_REL)). //
				andExpect(linkWithRelIsPresent(PAYMENT_REL)).//
				andReturn().getResponse();
	}

	/**
	 * Triggers the payment of an {@link Order} by following the {@code payment} link and submitting a credit card number
	 * to it. Verifies that we get a {@code 201 Created} and the response contains an {@code order} link. After the
	 * payment has been triggered we fake a cancellation to make sure it is rejected with a {@code 404 Not found}.
	 *
	 * @param response
	 * @return
	 * @throws Exception
	 */
	private MockHttpServletResponse triggerPayment(MockHttpServletResponse response) throws Exception {

		var content = response.getContentAsString();
		var discoverer = getDiscovererFor(response);
		var paymentLink = discoverer.findRequiredLinkWithRel(PAYMENT_REL, content);

		LOG.info(String.format("Discovered payment link pointing to %s…", paymentLink));

		assertThat(paymentLink).isNotNull();

		LOG.info("Triggering payment…");

		var action = mvc.perform(put(paymentLink.getHref())//
				.content("{ \"number\" : \"1234123412341234\" }")//
				.contentType(MediaType.APPLICATION_JSON)//
				.accept(MediaTypes.HAL_JSON));

		var result = action.andExpect(status().isCreated()). //
				andExpect(linkWithRelIsPresent(ORDER_REL)). //
				andReturn().getResponse();

		LOG.info("Payment triggered…");

		// Make sure we cannot cheat and cancel the order after it has been payed
		LOG.info("Faking a cancel request to make sure it's forbidden…");

		var selfLink = discoverer.findRequiredLinkWithRel(IanaLinkRelations.SELF, content);
		mvc.perform(delete(selfLink.getHref())).andExpect(status().isMethodNotAllowed());

		return result;
	}

	/**
	 * Polls the order resource every 2 seconds and uses an {@code If-None-Match} header alongside the {@code ETag} of the
	 * first response to avoid sending the representation over and over again.
	 *
	 * @param response
	 * @return
	 * @throws Exception
	 */
	private MockHttpServletResponse pollUntilOrderHasReceiptLink(MockHttpServletResponse response) throws Exception {

		// Grab
		var content = response.getContentAsString();
		var discoverer = getDiscovererFor(response);
		var orderLink = discoverer.findRequiredLinkWithRel(ORDER_REL, content);

		// Poll order until receipt link is set
		var receiptLink = Optional.<Link> empty();
		String etag = null;
		MockHttpServletResponse pollResponse;

		do {

			var headers = new HttpHeaders();

			if (etag != null) {
				headers.setIfNoneMatch(etag);
			}

			LOG.info("Poll state of order until receipt is ready…");

			var action = mvc.perform(get(orderLink.expand().getHref()).headers(headers));
			pollResponse = action.andReturn().getResponse();

			var status = pollResponse.getStatus();
			etag = pollResponse.getHeader("ETag");

			LOG.info(String.format("Received %s with ETag of %s…", status, etag));

			if (status == HttpStatus.OK.value()) {

				action.andExpect(linkWithRelIsPresent(IanaLinkRelations.SELF)). //
						andExpect(linkWithRelIsNotPresent(UPDATE_REL)). //
						andExpect(linkWithRelIsNotPresent(CANCEL_REL));

				receiptLink = discoverer.findLinkWithRel(RECEIPT_REL, pollResponse.getContentAsString());

			} else if (status == HttpStatus.NO_CONTENT.value()) {
				action.andExpect(content().string(is(emptyOrNullString())));
			}

			if (!receiptLink.isPresent()) {
				Thread.sleep(2000);
			}

		} while (!receiptLink.isPresent());

		return pollResponse;
	}

	/**
	 * Concludes the {@link Order} by looking up the {@code receipt} link from the response and follows it. Triggers a
	 * {@code DELETE} request subsequently.
	 *
	 * @param response
	 * @return
	 * @throws Exception
	 */
	private MockHttpServletResponse takeReceipt(MockHttpServletResponse response) throws Exception {

		var receiptLink = getDiscovererFor(response).findRequiredLinkWithRel(RECEIPT_REL, response.getContentAsString());

		var receiptResponse = mvc.perform(get(receiptLink.getHref())). //
				andExpect(status().isOk()). //
				andReturn().getResponse();

		LOG.info("Accessing receipt, got:" + receiptResponse.getContentAsString());
		LOG.info("Taking receipt…");

		return mvc.perform( //
				delete(receiptLink.getHref()).//
						accept(MediaTypes.HAL_JSON))
				. //
				andExpect(status().isOk()). //
				andReturn().getResponse();
	}

	/**
	 * Follows the {@code order} link and asserts only the self link being present so that no further navigation is
	 * possible anymore.
	 *
	 * @param response
	 * @throws Exception
	 */
	private void verifyOrderTaken(MockHttpServletResponse response) throws Exception {

		var orderLink = getDiscovererFor(response).findRequiredLinkWithRel(ORDER_REL, response.getContentAsString());
		var orderResponse = mvc.perform(get(orderLink.expand().getHref())). //
				andExpect(status().isOk()). // //
				andExpect(linkWithRelIsPresent(IanaLinkRelations.SELF)). //
				andExpect(linkWithRelIsNotPresent(UPDATE_REL)). //
				andExpect(linkWithRelIsNotPresent(CANCEL_REL)). //
				andExpect(linkWithRelIsNotPresent(PAYMENT_REL)). //
				andExpect(jsonPath("$.status", is("Delivered"))). //
				andReturn().getResponse();

		LOG.info("Final order state: " + orderResponse.getContentAsString());
	}

	/**
	 * Cancels the order by issuing a delete request. Verifies the resource being inavailable after that.
	 *
	 * @param response the response that retrieved an order resource
	 * @throws Exception
	 */
	private void cancelOrder(MockHttpServletResponse response) throws Exception {

		var content = response.getContentAsString();

		var discoverer = getDiscovererFor(response);
		var selfLink = discoverer.findRequiredLinkWithRel(IanaLinkRelations.SELF, content);
		var cancellationLink = discoverer.findRequiredLinkWithRel(CANCEL_REL, content);

		mvc.perform(delete(cancellationLink.getHref())).andExpect(status().isNoContent());
		mvc.perform(get(selfLink.getHref())).andExpect(status().isNotFound());
	}
}
