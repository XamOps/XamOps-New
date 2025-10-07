package com.xammer.cloud.controller;

import com.xammer.cloud.dto.ClientDto;
import com.xammer.cloud.service.ClientService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/clients")
public class ClientController {

    @Autowired
    private ClientService clientService;

    @PostMapping
    public ClientDto createClient(@RequestBody ClientDto clientDto) {
        return clientService.createClient(clientDto);
    }

    @GetMapping
    public List<ClientDto> getAllClients() {
        return clientService.getAllClients();
    }

    @PutMapping("/{id}")
    public ResponseEntity<ClientDto> updateClient(@PathVariable Long id, @RequestBody ClientDto clientDto) {
        return clientService.updateClient(id, clientDto)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}