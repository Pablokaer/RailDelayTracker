package com.irishrail.web;

import com.irishrail.controller.PageController;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * Open Graph and Twitter card images must be absolute URLs, because the crawler that reads them
 * (WhatsApp, Slack, LinkedIn...) fetches them with no page context. The base URL comes from the
 * request, so it follows the forwarded host/scheme behind the production proxy.
 */
@ControllerAdvice(assignableTypes = PageController.class)
public class SiteUrlAdvice {

    @ModelAttribute("siteUrl")
    public String siteUrl() {
        return ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString();
    }
}
