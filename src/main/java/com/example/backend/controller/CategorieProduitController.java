package com.example.backend.controller;

import com.example.backend.dto.CategorieProduitDTO;
import com.example.backend.entity.Administrateur;
import com.example.backend.entity.CategorieProduit;
import com.example.backend.entity.Utilisateur;
import com.example.backend.service.CategorieProduitService;
import com.example.backend.service.UtilisateurService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/categories")
public class CategorieProduitController {

    @Autowired
    private CategorieProduitService service;
    @Autowired
    private UtilisateurService utilisateurService;

    private Utilisateur getCurrentAdmin() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String username = authentication.getName();
        Utilisateur utilisateur = utilisateurService.findByNomUtilisateur(username);
        if (!(utilisateur instanceof Administrateur)) {
            throw new RuntimeException("Accès refusé : seul un administrateur peut gérer les catégories.");
        }
        return utilisateur;
    }

    @PostMapping
    public CategorieProduit create(@RequestBody CategorieProduitDTO categorieDTO) {
        getCurrentAdmin();
        CategorieProduit categorie = new CategorieProduit();
        categorie.setNom(categorieDTO.getNom());
        categorie.setDescription(categorieDTO.getDescription());
        return service.create(categorie);
    }

    @GetMapping
    public List<CategorieProduit> getAll() {
        return service.getAll();
    }

    @GetMapping("/{id}")
    public CategorieProduit getById(@PathVariable Long id) {
        return service.getById(id);
    }

    @PutMapping("/{id}")
    public CategorieProduit update(@PathVariable Long id, @RequestBody CategorieProduit categorie) {
        getCurrentAdmin(); // Vérifie que c'est un admin
        return service.update(id, categorie);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        getCurrentAdmin(); // Vérifie que c'est un admin
        service.delete(id);
    }
}
