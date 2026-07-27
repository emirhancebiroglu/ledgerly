package com.ledgerly.api.category;

/** A category with this name already exists in the organization. */
public class DuplicateCategoryNameException extends RuntimeException {

  public DuplicateCategoryNameException(String name) {
    super("Category name already exists in this organization: " + name);
  }
}
