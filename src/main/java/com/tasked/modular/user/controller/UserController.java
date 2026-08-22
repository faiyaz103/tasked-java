package com.tasked.modular.user.controller;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.tasked.modular.user.dtos.CreateUserDto;
import com.tasked.modular.user.service.UserService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;



@RestController
@RequestMapping("users")
@RequiredArgsConstructor
public class UserController {
    private final UserService userService;

    @GetMapping("hello")
    public String getHello() {
        return userService.getHello();
    }

    @PostMapping()
    @ResponseStatus(HttpStatus.CREATED)
    public String createUser(@Valid @RequestBody CreateUserDto dto) {
        
        return userService.createUser(dto);
    }
    
    
}
