package com.ledgerly.api.category;

import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Creates the editable baseline taxonomy every organization needs before its first upload. */
@Service
public class OrganizationCategoryProvisioner {

  private static final List<String> STARTER_CATEGORY_NAMES =
      List.of(
          "Software & Subscriptions",
          "Travel & Transport",
          "Meals & Entertainment",
          "Office & Supplies",
          "Professional Services",
          "Marketing & Advertising",
          "Utilities",
          "Equipment & Hardware",
          "Taxes & Fees",
          "Insurance",
          "Training & Education",
          "Other Operating Expenses");

  private final CategoryRepository categoryRepository;

  public OrganizationCategoryProvisioner(CategoryRepository categoryRepository) {
    this.categoryRepository = categoryRepository;
  }

  public void provision(UUID organizationId) {
    categoryRepository.saveAll(
        STARTER_CATEGORY_NAMES.stream().map(name -> new Category(organizationId, name)).toList());
  }
}
