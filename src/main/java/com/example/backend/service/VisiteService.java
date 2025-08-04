package com.example.backend.service;

import com.example.backend.dto.VisiteSimpleDTO;
import com.example.backend.entity.*;
import com.example.backend.repository.ClientRepository;
import com.example.backend.repository.VisiteRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class VisiteService {
    @Autowired
    private UtilisateurService utilisateurService;
    private Utilisateur getCurrentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String username = authentication.getName();

        Utilisateur utilisateur = utilisateurService.findByNomUtilisateur(username);
        if (utilisateur == null) {
            throw new RuntimeException("Utilisateur non trouvé : " + username);
        }
        return utilisateur;
    }

    private final VisiteRepository visiteRepository;
    private final ClientService clientService;
    private final PreVendeurService preVendeurService;
        @Autowired
        private ClientRepository clientRepository;

    public VisiteService(VisiteRepository visiteRepository,
                         ClientService clientService,
                         PreVendeurService preVendeurService) {
        this.visiteRepository = visiteRepository;
        this.clientService = clientService;
        this.preVendeurService = preVendeurService;
    }
    @Transactional
    public VisiteSimpleDTO modifierStatutVisite(Long visiteId, StatutVisite nouveauStatut, Utilisateur utilisateurConnecte) {
        Visite visite = visiteRepository.findById(visiteId)
                .orElseThrow(() -> new RuntimeException("Visite introuvable"));

        // Vérifie si l'utilisateur est le vendeur associé
        if (visite.getVendeur() != null && !visite.getVendeur().getId().equals(utilisateurConnecte.getId())) {
            throw new RuntimeException("Vous n'avez pas le droit de modifier cette visite");
        }

        visite.setStatut(nouveauStatut);
        visite = visiteRepository.save(visite);
        return toVisiteSimpleDTO(visite);
    }

    public List<VisiteSimpleDTO> getToutesLesVisites() {
        List<Visite> visites = visiteRepository.findAll();
        return visites.stream()
                .map(this::toVisiteSimpleDTO)
                .toList();
    }
    // Récupère toutes les visites (pour admin)
    public List<Visite> getToutesLesVisitesEntities() {
        return visiteRepository.findAll();
    }


    // Récupère les visites pour un vendeur ou pré-vendeur donné
    public List<Visite> getVisitesParVendeur(Long vendeurId) {
        List<Client> clients = clientRepository.findClientsByVendeurId(vendeurId);
        List<Long> clientIds = clients.stream()
                .map(Client::getId)
                .collect(Collectors.toList());

        return visiteRepository.findByClientIdIn(clientIds);
    }

    // Méthode pour créer une visite avec validation des règles métier
    private Visite createVisite(Client client, Utilisateur vendeur, LocalDate date) {
        Visite visite = new Visite();
        visite.setClient(client);
        visite.setVendeur(vendeur); // Peut être un PreVendeur ou VendeurDirect
        visite.setDatePlanifiee(date);
        visite.setStatut(StatutVisite.PLANIFIEE);

        // L’ID sera automatiquement généré ici
        return visiteRepository.save(visite);
    }
    public String genererContexteVisites() {
        Utilisateur utilisateur = getCurrentUser(); // méthode pour récupérer l'utilisateur connecté
        List<Visite> visites;

        // Règles d'accès
        if (utilisateur instanceof Administrateur || "ADMIN".equals(utilisateur.getRole())) {
            visites = getToutesLesVisitesEntities();
        } else if ("VENDEURDIRECT".equals(utilisateur.getRole()) || "PREVENDEUR".equals(utilisateur.getRole())) {
            visites =getVisitesParVendeur(utilisateur.getId());
        } else {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Accès réservé aux vendeurs et administrateurs");
        }

        // S'il n'y a pas de visites
        if (visites.isEmpty()) {
            return "Aucune visite planifiée ou réalisée.";
        }

        // Construction du contexte
        StringBuilder contexte = new StringBuilder("Historique des visites :\n");

        for (Visite visite : visites) {
            Long visiteId = visite.getId();
            String clientNom = (visite.getClient() != null) ? visite.getClient().getNom() : "Client inconnu";
            String vendeurNom = (visite.getVendeur() != null) ? visite.getVendeur().getNomUtilisateur() : "Non attribué";
            String typeClient = (visite.getClient() != null && visite.getClient().getType() != null)
                    ? visite.getClient().getType().name()
                    : "Type inconnu";
            String datePlanifiee = (visite.getDatePlanifiee() != null)
                    ? visite.getDatePlanifiee().toString()
                    : "Date non définie";
            String dateReelle = (visite.getDateReelle() != null)
                    ? visite.getDateReelle().toString()
                    : "Non encore réalisée";
            String statut = (visite.getStatut() != null) ? visite.getStatut().name() : "Statut inconnu";

            contexte.append("- [ID: ").append(visiteId)
                    .append("] Client: ").append(clientNom)
                    .append(" | Vendeur: ").append(vendeurNom)
                    .append(" | Type: ").append(typeClient)
                    .append(" | Date planifiée: ").append(datePlanifiee)
                    .append(" | Date réelle: ").append(dateReelle)
                    .append(" | Statut: ").append(statut)
                    .append("\n");
        }

        return contexte.toString();
    }

    // Nouvelle méthode optimisée pour votre TypeClient
    @Transactional
    public List<VisiteSimpleDTO> planifierVisitesAutomatiques() {
        List<Visite> visitesCreees = new ArrayList<>();
        List<Client> clients = clientService.getAllClients();
        LocalDate limiteDate = LocalDate.now().plusMonths(3);

        for (Client client : clients) {
            if (client.getType() == TypeClient.LIVREUR) continue;

            // Dernière date planifiée ou aujourd'hui si aucune
            LocalDate derniereDatePlanifiee = visiteRepository.findTopByClientOrderByDatePlanifieeDesc(client)
                    .map(Visite::getDatePlanifiee)
                    .orElse(LocalDate.now());

            LocalDate prochaineDate = derniereDatePlanifiee;

            while (!prochaineDate.isAfter(limiteDate)) {
                prochaineDate = calculateNextVisitDateByType(client.getType(), prochaineDate);
                if (prochaineDate == null) break;

                boolean existeDeja = visiteRepository.existsByClientAndDatePlanifieeBetween(
                        client,
                        prochaineDate.minusDays(1),
                        prochaineDate.plusDays(1)
                );

                if (existeDeja) continue;

                // ✅ Récupérer n’importe quel vendeur (PreVendeur ou VendeurDirect)
                Utilisateur vendeur = null;

                for (Route route : client.getRoutes()) {
                    Set<Utilisateur> vendeurs = route.getVendeurs();
                    if (vendeurs != null && !vendeurs.isEmpty()) {
                        vendeur = vendeurs.iterator().next(); // ✅ prend le premier élément du Set
                        break;
                    }

                }

                // Créer la visite même si vendeur == null
                Visite visite = createVisite(client, vendeur, prochaineDate);
                visitesCreees.add(visite);
            }
        }

        return visitesCreees.stream()
                .map(this::toVisiteSimpleDTO)
                .toList();
    }

    private LocalDate calculateNextVisitDateByType(TypeClient typeClient, LocalDate dateReference) {
        return switch (typeClient) {
            case HFS_HIGH_FREQUENCY_STORES, SMM_SUPERMARKETS -> dateReference.plusDays(3);
            case WHS_WHOLESALERS -> dateReference.plusDays(7);
            case PERF_PERFUMERIES -> dateReference.plusDays(5);
            case LIVREUR -> null;
        };
    }


    public VisiteSimpleDTO toVisiteSimpleDTO(Visite visite) {
        Client client = visite.getClient();
        Utilisateur vendeur = visite.getVendeur();

        return new VisiteSimpleDTO(
                visite.getId(),
                visite.getDatePlanifiee(),
                client.getNom(),
                vendeur != null ? vendeur.getNomUtilisateur() : "Non attribué",
                client.getType(),
                client.getAdresse(),
                client.getTelephone(),
                client.getEmail(),
                visite.getStatut()  // ok, c'est un StatutVisite
        );


    }







    // Validation renforcée des dates de visite
    public boolean isVisitDateValid(Client client, LocalDate proposedDate) {
        if (proposedDate.isBefore(LocalDate.now())) {
            return false;
        }

        LocalDate derniereVisite = getLastVisitDate(client);
        if (derniereVisite == null) {
            return true;
        }

        return switch(client.getType()) {
            case HFS_HIGH_FREQUENCY_STORES, SMM_SUPERMARKETS ->
                    proposedDate.isAfter(derniereVisite.plusDays(2)); // Au moins 3 jours complets
            case WHS_WHOLESALERS ->
                    proposedDate.isAfter(derniereVisite.plusDays(6));
            case PERF_PERFUMERIES ->
                    proposedDate.isAfter(derniereVisite.plusDays(4));
            case LIVREUR ->
                    false;
        };
    }

    // Méthodes utilitaires
    private LocalDate getLastVisitDate(Client client) {
        return visiteRepository.findTopByClientOrderByDateReelleDesc(client)
                .map(Visite::getDateReelle)
                .orElse(null);
    }

    // Gestion des reports avec journalisation
    @Transactional
    public Visite reporterVisite(Long visiteId, LocalDate newDate, String raison) {
        Visite visite = visiteRepository.findById(visiteId)
                .orElseThrow(() -> new RuntimeException("Visite non trouvée"));



        visite.setDatePlanifiee(newDate);
        visite.setStatut(StatutVisite.REPORTEE);
        // Ajouter un champ 'commentaireReport' si nécessaire

        return visiteRepository.save(visite);
    }

    // Pour le dashboard superviseur
    public List<VisiteSimpleDTO> getProchainesVisitesCritiques() {
        return visiteRepository.findVisitesSimplesByClientTypeAndDateRange(
                List.of(TypeClient.HFS_HIGH_FREQUENCY_STORES, TypeClient.SMM_SUPERMARKETS),
                LocalDate.now(),
                LocalDate.now().plusDays(3)
        );
    }


}