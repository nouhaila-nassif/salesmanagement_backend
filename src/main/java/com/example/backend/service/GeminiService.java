package com.example.backend.service;
import com.example.backend.entity.*;
import com.example.backend.repository.ClientRepository;
import com.example.backend.repository.ProduitRepository;
import com.example.backend.repository.UtilisateurRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.HttpHeaders;

import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class GeminiService {

    @Value("${gemini.api.key}")
    private String apiKey;
    @Autowired
    private UtilisateurRepository utilisateurRepository;
    @Value("${gemini.api.url}")
    private String apiUrl;
    @Autowired
    private ClientRepository clientRepository;
    private final RestTemplate restTemplate = new RestTemplate();
    @Autowired
    UtilisateurService  utilisateurService;
    @Autowired
    private CommandeService commandeService;
    @Autowired
    private ProduitRepository produitRepository;


    private Map<String, Integer> extraireProduits(String query) {
        Map<String, Integer> produits = new HashMap<>();
        if (query == null || query.isBlank()) return produits;

        // Extraire uniquement la partie après "avec les produits"
        String[] split = query.toLowerCase().split("avec les produits", 2);
        if (split.length < 2) return produits;

        String produitsBruts = split[1].trim();

        // Séparer chaque item par virgule ou "et"
        String[] items = produitsBruts.split("\\s*(,|et)\\s*");

        for (String item : items) {
            item = item.trim();

            // ✅ Nouveau format : "NomProduit*Quantité"
            if (item.contains("*")) {
                String[] parts = item.split("\\*");
                if (parts.length == 2) {
                    String nomProduit = parts[0].trim();
                    try {
                        int quantite = Integer.parseInt(parts[1].trim());
                        produits.put(nomProduit, quantite);
                        continue;
                    } catch (NumberFormatException e) {
                        // ignorer et continuer avec d'autres formats
                    }
                }
            }

            // 🧠 Sinon, tenter les anciens formats : "3 shampoing", "shampoing 3"
            String regex1 = "(\\d+)\\s+([a-zA-Z\\s]+)";
            String regex2 = "([a-zA-Z\\s]+)\\s+(\\d+)";
            Matcher matcher = Pattern.compile(regex1).matcher(item);
            if (matcher.find()) {
                produits.put(matcher.group(2).trim(), Integer.parseInt(matcher.group(1).trim()));
                continue;
            }

            matcher = Pattern.compile(regex2).matcher(item);
            if (matcher.find()) {
                produits.put(matcher.group(1).trim(), Integer.parseInt(matcher.group(2).trim()));
            }
        }

        return produits;
    }
    public String corrigerCommandeAvecGemini(String texteBrut) {
        String nomsClients = clientRepository.findAll()
                .stream()
                .map(Client::getNom)
                .collect(Collectors.joining(", "));

        String nomsProduits = produitRepository.findAll()
                .stream()
                .map(Produit::getNom)
                .collect(Collectors.joining(", "));

        String prompt = """
Tu es un assistant qui corrige et structure les commandes commerciales.

Voici la liste des clients valides : [%s]
Voici la liste des produits valides : [%s]

À partir du texte ci-dessous (même mal écrit), fais les 2 choses suivantes :
1. Corrige les fautes (orthographe, accents, grammaire)
2. Identifie clairement :
    - Le nom exact du client (existant dans la liste)
    - Les produits (noms + quantités) même si mal orthographiés

Retourne le résultat dans ce format JSON strict :

{
  "client": "Nom Client",
  "produits": [
    { "nom": "Produit A", "quantite": 3 },
    { "nom": "Produit B", "quantite": 2 }
  ]
}

Commande reçue : "%s"
""".formatted(nomsClients, nomsProduits, texteBrut);

        return askGemini(prompt, "");
    }
    private String extraireClient(String query) {
        if (query == null || query.isBlank()) {
            throw new RuntimeException("Commande vide.");
        }

        // Regex pour capturer le client après "commande à" ou "commande pour"
        String regex = "(?i)commande\\s+(?:à|a|pour)\\s+([\\w\\s\\-\\.]+?)(?=\\s+avec|\\s+produits|:|$)";

        Pattern pattern = Pattern.compile(regex);
        Matcher matcher = pattern.matcher(query);

        if (matcher.find()) {
            String client = matcher.group(1).trim();
            if (!client.isEmpty()) {
                return client;
            }
        }

        throw new RuntimeException("Impossible d'extraire le nom du client.");
    }

    private Utilisateur getCurrentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated() || authentication.getPrincipal().equals("anonymousUser")) {
            return null;  // Pas d’utilisateur connecté
        }
        String username = authentication.getName();
        return utilisateurService.findByNomUtilisateur(username);
    }
    private boolean isValidJSON(String json) {
        try {
            final ObjectMapper mapper = new ObjectMapper();
            mapper.readTree(json);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    public String traiterCommandeDepuisJsonGemini(String query) {
        try {
            // Étape 1 : Analyser la requête avec regex
            Pattern pattern = Pattern.compile(
                    ".*de\\s+(\\d+[\\.,]?\\d*)\\s+(\\d+)\\s+pour le client\\s+(\\d+).*",
                    Pattern.CASE_INSENSITIVE
            );
            Matcher matcher = pattern.matcher(query);

            if (!matcher.find() || matcher.groupCount() < 3) {
                return "{\"result\": \"❌ Requête invalide. Format attendu : 'Créer une commande de <quantité> <produitId> pour le client <clientId>'\"}";
            }

            // Étape 2 : Extraire quantité, produitId et clientId
            int quantite = (int) Double.parseDouble(matcher.group(1).replace(",", "."));
            Long produitId = Long.parseLong(matcher.group(2));
            Long clientId = Long.parseLong(matcher.group(3));

            // Étape 3 : Vérifier existence client et produit
            Client client = clientRepository.findById(clientId)
                    .orElseThrow(() -> new RuntimeException("Client introuvable avec ID : " + clientId));
            Produit produit = produitRepository.findById(produitId)
                    .orElseThrow(() -> new RuntimeException("Produit introuvable avec ID : " + produitId));

            // Étape 4 : Créer la commande
            Commande commande = new Commande();
            commande.setClient(client);
            commande.setDateCreation(LocalDate.now());
            commande.setDateLivraison(LocalDate.now().plusDays(3));
            commande.setStatut(StatutCommande.NON_LIVREE);

            LigneCommande ligne = new LigneCommande();
            ligne.setProduit(produit);
            ligne.setQuantite(quantite);
            ligne.setPrixUnitaire(produit.getPrixUnitaire());
            ligne.setCommande(commande);

            commande.setLignes(Collections.singletonList(ligne));

            // Étape 5 : Obtenir vendeur connecté
            Utilisateur vendeur = getCurrentUser();
            if (vendeur == null) {
                return "{\"result\": \"❌ Aucun utilisateur connecté.\"}";
            }

            // Étape 6 : Sauvegarder la commande
            Commande saved = commandeService.creerCommande(commande, vendeur);

            // Étape 7 : Construire le message enrichi
            StringBuilder resultMessage = new StringBuilder();
            resultMessage.append("✅ La commande n°")
                    .append(saved.getId())
                    .append(" a été créée avec succès le ")
                    .append(saved.getDateCreation())
                    .append(". Elle concerne les produits suivants : ");

            List<String> produits = new ArrayList<>();
            double total = 0.0;
            for (LigneCommande l : saved.getLignes()) {
                produits.add(l.getProduit().getNom() + " x" + l.getQuantite());
                total += l.getPrixUnitaire() * l.getQuantite();
            }

            resultMessage.append(String.join(", ", produits))
                    .append(". Le montant total est de ")
                    .append(String.format("%.2f DH", total))
                    .append(". La livraison est prévue pour le ")
                    .append(saved.getDateLivraison())
                    .append(". Statut actuel : ")
                    .append(saved.getStatut().name())
                    .append(".");

            ObjectMapper mapper = new ObjectMapper();
            ObjectNode root = mapper.createObjectNode();
            root.put("result", resultMessage.toString());

            return mapper.writeValueAsString(root);

        } catch (Exception e) {
            e.printStackTrace();
            return "{\"result\": \"❌ Erreur lors du traitement : " + e.getMessage().replace("\"", "\\\"") + "\"}";
        }
    }


    private String nettoyerJsonBrut(String raw) {
        if (raw == null) return null;
        raw = raw.trim();
        if (raw.startsWith("```json")) {
            raw = raw.substring("```json".length()).trim();
        }
        if (raw.endsWith("```")) {
            raw = raw.substring(0, raw.length() - 3).trim();
        }
        return raw;
    }

    public String remplacerDatesRelatives(String query) {
        LocalDate aujourdHui = LocalDate.now();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");

        if (query.toLowerCase().contains("demain")) {
            String dateDemain = aujourdHui.plusDays(1).format(formatter);
            query = query.replaceAll("(?i)demain", dateDemain);
        }

        if (query.toLowerCase().contains("aujourd'hui")) {
            String dateAujourdhui = aujourdHui.format(formatter);
            query = query.replaceAll("(?i)aujourd'hui", dateAujourdhui);
        }

        if (query.toLowerCase().contains("hier")) {
            String dateHier = aujourdHui.minusDays(1).format(formatter);
            query = query.replaceAll("(?i)hier", dateHier);
        }

        return query;
    }
    public String askGemini(String query, String context) {
        try {
            String prompt = "Context:\n" + context + "\n\nQuestion:\n" + query + "\n\nAnswer using only the provided context.";

            Map<String, Object> requestBody = new HashMap<>();
            List<Map<String, Object>> parts = List.of(Map.of("text", prompt));
            requestBody.put("contents", List.of(Map.of("parts", parts)));

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("x-goog-api-key", apiKey);

            HttpEntity<Map<String, Object>> request = new HttpEntity<>(requestBody, headers);

            ResponseEntity<Map> response = restTemplate.postForEntity(apiUrl, request, Map.class);

            List<Map> candidates = (List<Map>) response.getBody().get("candidates");
            Map content = (Map) candidates.get(0).get("content");
            List<Map> partsResponse = (List<Map>) content.get("parts");

            return partsResponse.get(0).get("text").toString();

        } catch (Exception e) {
            e.printStackTrace();
            return "Erreur avec Gemini";
        }
    }

}

