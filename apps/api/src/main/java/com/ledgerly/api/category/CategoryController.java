package com.ledgerly.api.category;

import com.ledgerly.api.auth.AuthenticatedPrincipal;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CategoryController {

  private final CategoryService categoryService;

  public CategoryController(CategoryService categoryService) {
    this.categoryService = categoryService;
  }

  @PostMapping("/api/v1/categories")
  @ResponseStatus(HttpStatus.CREATED)
  public CategoryResponse create(
      @Valid @RequestBody CategoryRequest request,
      @AuthenticationPrincipal AuthenticatedPrincipal principal) {
    return CategoryResponse.from(categoryService.create(request.name(), principal));
  }

  @GetMapping("/api/v1/categories")
  public List<CategoryResponse> list(@AuthenticationPrincipal AuthenticatedPrincipal principal) {
    return categoryService.list(principal).stream().map(CategoryResponse::from).toList();
  }

  @GetMapping("/api/v1/categories/{id}")
  public CategoryResponse get(
      @PathVariable UUID id, @AuthenticationPrincipal AuthenticatedPrincipal principal) {
    return CategoryResponse.from(categoryService.get(id, principal));
  }

  @PutMapping("/api/v1/categories/{id}")
  public CategoryResponse rename(
      @PathVariable UUID id,
      @Valid @RequestBody CategoryRequest request,
      @AuthenticationPrincipal AuthenticatedPrincipal principal) {
    return CategoryResponse.from(categoryService.rename(id, request.name(), principal));
  }

  @DeleteMapping("/api/v1/categories/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(
      @PathVariable UUID id, @AuthenticationPrincipal AuthenticatedPrincipal principal) {
    categoryService.delete(id, principal);
  }
}
