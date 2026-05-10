package com.techzo.cambiazo.exchanges.application.internal.queryservices;

import com.techzo.cambiazo.exchanges.domain.model.dtos.Location;
import com.techzo.cambiazo.exchanges.domain.model.dtos.ProductDto;
import com.techzo.cambiazo.exchanges.domain.model.entities.*;
import com.techzo.cambiazo.exchanges.domain.model.queries.GetAllProductsByProductCategoryIdQuery;
import com.techzo.cambiazo.exchanges.domain.model.queries.GetAllProductsByUserIdQuery;
import com.techzo.cambiazo.exchanges.domain.model.queries.GetAllProductsQuery;
import com.techzo.cambiazo.exchanges.domain.model.queries.GetProductByIdQuery;
import com.techzo.cambiazo.exchanges.domain.services.IProductQueryService;
import com.techzo.cambiazo.exchanges.infrastructure.persistence.jpa.*;
import com.techzo.cambiazo.iam.domain.model.aggregates.User;
import com.techzo.cambiazo.iam.infrastructure.persistence.jpa.repositories.UserRepository;
import com.techzo.cambiazo.iam.interfaces.rest.transform.UserResource2FromEntityAssembler;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class ProductQueryServiceImpl implements IProductQueryService {

    private final IProductRepository productRepository;

    private final UserRepository userRepository;

    private final IProductCategoryRepository productCategoryRepository;

    private final IDistrictRepository districtRepository;

    private final IDepartmentRepository departmentRepository;

    private final ICountryRepository countryRepository;

    public ProductQueryServiceImpl(
            IProductRepository productRepository,
            UserRepository userRepository,
            IProductCategoryRepository productCategoryRepository,
            IDistrictRepository districtRepository,
            IDepartmentRepository departmentRepository,
            ICountryRepository countryRepository
    ){
        this.productRepository=productRepository;
        this.productCategoryRepository = productCategoryRepository;
        this.userRepository = userRepository;
        this.districtRepository = districtRepository;
        this.departmentRepository = departmentRepository;
        this.countryRepository = countryRepository;
    }


    @Override
    public Optional<ProductDto> handle(GetProductByIdQuery query) {
        Product product = productRepository.findByIdWithRelations(query.id())
                .orElseThrow(() -> new IllegalArgumentException("Product with id " + query.id() + " not found"));
        return Optional.of(toDto(product));
    }

    private ProductDto toDto(Product product) {
        District district = product.getDistrict();
        Department department = district.getDepartment();
        Country country = department.getCountry();
        Location location = new Location(district.getId(), district.getName(), department.getId(), department.getName(), country.getId(), country.getName());
        var userResource = UserResource2FromEntityAssembler.toResourceFromEntity(product.getUser());
        return new ProductDto(
                product.getId(),
                product.getName(),
                product.getDescription(),
                product.getDesiredObject(),
                product.getPrice(),
                product.getImage(),
                product.getBoost(),
                product.getAvailable(),
                userResource,
                product.getProductCategory(),
                location,
                product.getCreatedAt()
        );
    }

    @Override
    public List<ProductDto> handle(GetAllProductsQuery query) {
        return productRepository.findAllWithRelations().stream()
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    @Override
    public List<ProductDto> handle(GetAllProductsByUserIdQuery query) {
        User user = userRepository.findById(query.userId())
                .orElseThrow(() -> new IllegalArgumentException("User with id " + query.userId() + " not found"));
        return productRepository.findProductsByUserIdWithRelations(user).stream()
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    @Override
    public List<ProductDto> handle(GetAllProductsByProductCategoryIdQuery query) {
        ProductCategory productCategory = productCategoryRepository.findById(query.productCategoryId())
                .orElseThrow(() -> new IllegalArgumentException("Product Category with id " + query.productCategoryId() + " not found"));
        return productRepository.findProductsByProductCategoryIdWithRelations(productCategory).stream()
                .map(this::toDto)
                .collect(Collectors.toList());
    }
}
