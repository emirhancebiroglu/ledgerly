package com.ledgerly.api.category;

/** A category cannot be deleted because an expense still references it. */
public class CategoryInUseException extends RuntimeException {

  public CategoryInUseException(String message) {
    super(message);
  }
}
