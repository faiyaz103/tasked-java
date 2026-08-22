package com.tasked.modular.user.service;

import org.springframework.stereotype.Service;

import com.tasked.modular.user.dtos.CreateUserDto;

@Service
public class UserService {
    public String getHello(){
        return "hello SpringBoot";
    }

    // create user
    public String createUser(CreateUserDto dto){
        return "email: "+dto.email()+" password: "+dto.password();
    }
}
