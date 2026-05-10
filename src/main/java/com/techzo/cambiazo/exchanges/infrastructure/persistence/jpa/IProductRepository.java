package com.techzo.cambiazo.exchanges.infrastructure.persistence.jpa;


import com.techzo.cambiazo.exchanges.domain.model.entities.Product;
import com.techzo.cambiazo.exchanges.domain.model.entities.ProductCategory;
import com.techzo.cambiazo.iam.domain.model.aggregates.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Date;
import java.util.List;
import java.util.Optional;

@Repository
public interface IProductRepository extends JpaRepository<Product, Long> {
    boolean existsByNameAndId(String name, Long id);

    @Query("""
            SELECT DISTINCT p FROM Product p
            LEFT JOIN FETCH p.productCategoryId
            LEFT JOIN FETCH p.userId u
            LEFT JOIN FETCH u.roles
            LEFT JOIN FETCH p.districtId d
            LEFT JOIN FETCH d.departmentId dep
            LEFT JOIN FETCH dep.countryId
            """)
    List<Product> findAllWithRelations();

    @Query("""
            SELECT DISTINCT p FROM Product p
            LEFT JOIN FETCH p.productCategoryId
            LEFT JOIN FETCH p.userId u
            LEFT JOIN FETCH u.roles
            LEFT JOIN FETCH p.districtId d
            LEFT JOIN FETCH d.departmentId dep
            LEFT JOIN FETCH dep.countryId
            WHERE p.id = :id
            """)
    Optional<Product> findByIdWithRelations(@Param("id") Long id);

    @Query("""
            SELECT DISTINCT p FROM Product p
            LEFT JOIN FETCH p.productCategoryId
            LEFT JOIN FETCH p.userId u
            LEFT JOIN FETCH u.roles
            LEFT JOIN FETCH p.districtId d
            LEFT JOIN FETCH d.departmentId dep
            LEFT JOIN FETCH dep.countryId
            WHERE p.userId = :user
            """)
    List<Product> findProductsByUserIdWithRelations(@Param("user") User user);

    @Query("""
            SELECT DISTINCT p FROM Product p
            LEFT JOIN FETCH p.productCategoryId
            LEFT JOIN FETCH p.userId u
            LEFT JOIN FETCH u.roles
            LEFT JOIN FETCH p.districtId d
            LEFT JOIN FETCH d.departmentId dep
            LEFT JOIN FETCH dep.countryId
            WHERE p.productCategoryId = :category
            """)
    List<Product> findProductsByProductCategoryIdWithRelations(@Param("category") ProductCategory category);

    List<Product>findProductsByUserId(User userId);

    List<Product>findProductsByProductCategoryId(ProductCategory productCategoryId);

    @Query("SELECT COUNT(p) FROM Product p WHERE p.userId = :userId AND p.createdAt > :createdAt")
    Long countByUserIdAndCreatedAtAfter(User userId, Date createdAt);

    @Query("SELECT COUNT(p) FROM Product p WHERE p.userId = :userId AND p.createdAt > :createdAt AND p.boost=true")
    Long countBoostsByUserIdAndCreatedAtAfter(User userId, Date createdAt);

    @Modifying
    @Query("UPDATE Product p SET p.available = false WHERE p.userId = :user")
    void updateProductAvailabilityByUser(@Param("user") User user);
}
