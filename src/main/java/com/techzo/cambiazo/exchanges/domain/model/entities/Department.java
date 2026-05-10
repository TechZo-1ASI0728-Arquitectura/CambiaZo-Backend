package com.techzo.cambiazo.exchanges.domain.model.entities;


import com.techzo.cambiazo.exchanges.domain.model.commands.CreateDepartmentCommand;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
@Entity
public class Department {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    @NotNull(message = "Name is required")
    private String name;

    @ManyToOne
    @JoinColumn(name = "country_id", nullable = false)
    @NotNull(message = "Country is required")
    private Country countryId;

    public Department() {
    }

    public Department(CreateDepartmentCommand command, Country country) {
        this.name = command.name();
        this.countryId = country;
    }

    public Long getCountryId() {
        return countryId.getId();
    }

    public Country getCountry() {
        return countryId;
    }
}
