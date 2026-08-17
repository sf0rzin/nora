package br.com.nora.api.api.dto.iam;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * A built-in policy template (US41), already bound to the caller's tenant.
 *
 * <p>{@code document} is in the very shape {@code POST /iam/policies} accepts, so instantiating a
 * template is submitting this field to that endpoint. There is no instantiate endpoint on purpose:
 * a policy made from a template has to be the same object as one typed by hand, and the surest way
 * to keep it so is for both to travel the same handler.
 *
 * <p>{@code id} doubles as the suggested policy name and is the key the UI translates for display.
 */
public record PolicyTemplateDto(String id, String description, JsonNode document) {}
