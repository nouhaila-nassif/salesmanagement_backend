package com.example.backend.service;

import com.example.backend.entity.CategorieProduit;
import com.example.backend.repository.CategorieProduitRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class CategorieProduitService {

    @Autowired
    private CategorieProduitRepository catégorieProduitRepository;

    public CategorieProduit create(CategorieProduit categorie) {
        return catégorieProduitRepository.save(categorie);
    }

    public List<CategorieProduit> getAll() {
        return catégorieProduitRepository.findAll();
    }

    public CategorieProduit getById(Long id) {
        return catégorieProduitRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Catégorie non trouvée"));
    }

    public CategorieProduit update(Long id, CategorieProduit newData) {
        CategorieProduit existing = getById(id);
        existing.setNom(newData.getNom());
        return catégorieProduitRepository.save(existing);
    }

    public void delete(Long id) {
        catégorieProduitRepository.deleteById(id);
    }
}
