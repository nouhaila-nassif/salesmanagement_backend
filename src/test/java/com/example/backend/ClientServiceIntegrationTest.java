package com.example.backend;

import com.example.backend.entity.Client;
import com.example.backend.entity.Route;
import com.example.backend.repository.ClientRepository;
import com.example.backend.repository.RouteRepository;
import com.example.backend.service.ClientService;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest  // Démarre le contexte Spring complet
public class ClientServiceIntegrationTest {

    @Autowired
    private ClientService clientService;

    @Autowired
    private ClientRepository clientRepository;

    @Autowired
    private RouteRepository routeRepository;

    @Test
    @Transactional  // pour rollback après chaque test et ne pas polluer la BDD
    public void testCreateClientWithRoute() {
        // Créer une route en base
        Route route = new Route();
        route.setNom("Route Test");
        Route savedRoute = routeRepository.save(route);
        assertNotNull(savedRoute.getId(), "Route doit avoir un ID après sauvegarde");

        // Créer un client
        Client client = new Client();
        client.setNom("Client Test");
        client.setTelephone("0600000000");

        // Appeler la méthode du service pour créer le client et associer la route
        Client savedClient = clientService.createClient(client, savedRoute.getId());

        // Vérifier que le client a bien un ID
        assertNotNull(savedClient.getId(), "Client doit avoir un ID après création");

        // Vérifier que la route est associée au client
        assertNotNull(savedClient.getRoutes(), "Liste des routes ne doit pas être null");
        assertTrue(savedClient.getRoutes().contains(savedRoute), "Le client doit contenir la route associée");

        // Récupérer le client depuis la base via le service
        Client retrievedClient = clientService.getClientById(savedClient.getId());
        assertEquals("Client Test", retrievedClient.getNom());
        assertEquals("0600000000", retrievedClient.getTelephone());

        // Vérifier que la relation est bien persistée
        assertFalse(retrievedClient.getRoutes().isEmpty(), "Le client doit avoir au moins une route");
        assertTrue(
                retrievedClient.getRoutes().stream()
                        .anyMatch(r -> r.getId().equals(savedRoute.getId())),
                "La route associée doit être présente"
        );
    }
}
