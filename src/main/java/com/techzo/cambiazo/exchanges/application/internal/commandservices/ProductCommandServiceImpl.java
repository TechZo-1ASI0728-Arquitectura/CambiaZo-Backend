package com.techzo.cambiazo.exchanges.application.internal.commandservices;


import com.techzo.cambiazo.exchanges.domain.model.commands.CreateProductCommand;
import com.techzo.cambiazo.exchanges.domain.model.commands.DeleteProductOfPendingExchangesCommand;
import com.techzo.cambiazo.exchanges.domain.model.commands.UpdateProductCommand;
import com.techzo.cambiazo.exchanges.domain.model.entities.*;
import com.techzo.cambiazo.exchanges.domain.services.IProductCommandService;
import com.techzo.cambiazo.exchanges.infrastructure.persistence.jpa.*;
import com.techzo.cambiazo.iam.domain.model.aggregates.User;
import com.techzo.cambiazo.iam.infrastructure.persistence.jpa.repositories.UserRepository;
import com.techzo.cambiazo.shared.infrastructure.storage.AzureBlobStorageService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Optional;

@Service
public class ProductCommandServiceImpl implements IProductCommandService {

    private final IProductRepository productRepository;

    private final UserRepository userRepository;

    private final IProductCategoryRepository productCategoryRepository;

    private final IDistrictRepository districtRepository;

    private final IFavoriteProductRepository favoriteProductRepository;

    private final ISubscriptionRepository subscriptionRepository;

    private final IPlanRepository planRepository;

    private final IExchangeRepository exchangeRepository;

    private final AzureBlobStorageService storageService;

    public ProductCommandServiceImpl(IProductRepository productRepository, UserRepository userRepository, IProductCategoryRepository productCategoryRepository, IDistrictRepository districtRepository, IFavoriteProductRepository favoriteProductRepository, ISubscriptionRepository subscriptionRepository, IPlanRepository planRepository, IExchangeRepository exchangeRepository, AzureBlobStorageService storageService) {
        this.productRepository = productRepository;
        this.userRepository = userRepository;
        this.productCategoryRepository = productCategoryRepository;
        this.districtRepository = districtRepository;
        this.favoriteProductRepository = favoriteProductRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.planRepository = planRepository;
        this.exchangeRepository = exchangeRepository;
        this.storageService = storageService;
    }

    @Override
    public Optional<Product> handle(CreateProductCommand command) {
        User user = userRepository.findById(command.userId())
                .orElseThrow(() -> new IllegalArgumentException("User with id not found"));

        Subscription subscription = subscriptionRepository.findSubscriptionActiveByUserId(user)
                .orElseThrow(() -> new IllegalArgumentException("Active subscription not found for user"));

        Plan plan = planRepository.findById(subscription.getPlanId())
                .orElseThrow(() -> new IllegalArgumentException("Plan with id not found"));

        if(plan.getName().equals("Lite") && command.boost().equals(true)){
            throw new IllegalArgumentException("El plan Free no permite Boost");
        }


        // Inicializar el inicio del mes para contar productos publicados
        Calendar calendar = Calendar.getInstance();
        calendar.set(Calendar.DAY_OF_MONTH, 1);
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        Date initMonth = calendar.getTime();

        // Contar los productos publicados por el usuario en el mes actual
        Long countProductsPublished = productRepository.countByUserIdAndCreatedAtAfter(user, initMonth);
        System.out.println("Productos publicados este mes: " + countProductsPublished);

        // Verificar el límite de productos según el plan
        switch (plan.getName()) {
            case "Lite":
                if (countProductsPublished >= 3) {
                    throw new IllegalArgumentException("Has alcanzado el límite de productos para el plan Free este mes.");
                }
                break;
            case "Plus":
                if (countProductsPublished >= 10) {
                    throw new IllegalArgumentException("Has alcanzado el límite de productos para el plan Plus este mes.");
                }
                break;
            case "Premium":
                // Premium no tiene límite de productos
                break;
            default:
                throw new IllegalArgumentException("Plan desconocido.");
        }

        if(command.boost().equals(true)){
            // Establecer el período de Boost según el plan
            Date initPeriod;
            if ("Lite".equals(plan.getName())) {
                initPeriod = null; // El plan Free no permite Boost, así que no hay periodo de Boost.
            } else if ("Plus".equals(plan.getName())) {
                initPeriod = Date.from(LocalDateTime.now().minusDays(7).atZone(ZoneId.systemDefault()).toInstant());
            } else if ("Premium".equals(plan.getName())) {
                initPeriod = Date.from(LocalDateTime.now().minusDays(1).atZone(ZoneId.systemDefault()).toInstant());
            } else {
                throw new IllegalArgumentException("Plan desconocido.");
            }

            // Si el plan permite Boost, contar cuántos Boost se han usado en el período
            if (initPeriod != null) {
                Long countUsedBoosts = productRepository.countBoostsByUserIdAndCreatedAtAfter(user, initPeriod);
                if ((plan.getName().equals("Plus") && countUsedBoosts >= 3) ||
                        (plan.getName().equals("Premium") && countUsedBoosts >= 1)) {
                    throw new IllegalArgumentException("Has alcanzado el límite de Boost para tu plan.");
                }
            }
        }

        ProductCategory productCategory = productCategoryRepository.findById(command.productCategoryId())
                .orElseThrow(() -> new IllegalArgumentException("Product Category with id not found"));
        District district = districtRepository.findById(command.districtId())
                .orElseThrow(() -> new IllegalArgumentException("District with id not found"));

        try {
            var product = new Product(command, productCategory, user, district);
            productRepository.save(product);
            return Optional.of(product);
        } catch (Exception e) {
            throw new IllegalArgumentException("Error while creating product: " + e.getMessage());
        }
    }

    @Override
    public Optional<Product>handle(UpdateProductCommand command){

        var result=productRepository.findById(command.id());

        if(result.isEmpty()){
            throw new IllegalArgumentException("Product with id not found");
        }

        var productToUpdate=result.get();

        try {
            ProductCategory productCategory = productCategoryRepository.findById(command.productCategoryId()).
                    orElseThrow(() -> new IllegalArgumentException("Product Category with id not found"));

            User user = userRepository.findById(command.userId()).
                    orElseThrow(() -> new IllegalArgumentException("User with id not found"));

            District district = districtRepository.findById(command.districtId()).
                    orElseThrow(() -> new IllegalArgumentException("District with id not found"));

            var updateProduct=productRepository.save(
                    productToUpdate.updateInformation(command.name(), command.description(),
                            command.desiredObject(), command.price(), command.image(), command.boost(),
                            command.available(), productCategory, user, district)
            );
            return Optional.of(updateProduct);
        }catch (Exception e){
            throw new IllegalArgumentException("Error while updating product: " + e.getMessage());
        }

    }





    @Override
    public boolean handleDeleteProduct(DeleteProductOfPendingExchangesCommand command) {
        Optional<Product> product = productRepository.findById(command.id());
        if (product.isEmpty()) {
            throw new IllegalArgumentException("Product not found");
        }
        List<FavoriteProduct> favoriteProducts = favoriteProductRepository.findFavoriteProductsByProductId(product.get());

        List<Exchange> exchanges = exchangeRepository.findAllExchangesByProductOwnIdOrProductChangeId(product.get());

        String imageUrl = product.get().getImage();

        exchangeRepository.deleteAll(exchanges);
        favoriteProductRepository.deleteAll(favoriteProducts);
        productRepository.delete(product.get());

        try {
            storageService.deleteByUrl(imageUrl);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return true;
    }
}
