package com.example.backend.repository;

import com.example.backend.entity.CategorieProduit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;


public interface CategorieProduitRepository extends JpaRepository<CategorieProduit, Long> {
    Optional<CategorieProduit> findByNom(String nom);

}
