package com.xammer.cloud.service;

import com.xammer.cloud.domain.Client;
import com.xammer.cloud.dto.ClientDto;
import com.xammer.cloud.repository.ClientRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class ClientService {

    @Autowired
    private ClientRepository clientRepository;

    public ClientDto createClient(ClientDto clientDto) {
        Client client = new Client();
        client.setName(clientDto.getName());
        client.setEmail(clientDto.getEmail());
        client = clientRepository.save(client);
        clientDto.setId(client.getId());
        return clientDto;
    }

    public List<ClientDto> getAllClients() {
        return clientRepository.findAll().stream().map(client -> {
            ClientDto dto = new ClientDto();
            dto.setId(client.getId());
            dto.setName(client.getName());
            dto.setEmail(client.getEmail());
            return dto;
        }).collect(Collectors.toList());
    }

    public Optional<ClientDto> updateClient(Long id, ClientDto clientDto) {
        return clientRepository.findById(id).map(client -> {
            client.setName(clientDto.getName());
            client.setEmail(clientDto.getEmail());
            client = clientRepository.save(client);
            ClientDto dto = new ClientDto();
            dto.setId(client.getId());
            dto.setName(client.getName());
            dto.setEmail(client.getEmail());
            return dto;
        });
    }
}