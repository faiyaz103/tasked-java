package com.tasked.modular.user.service.impl;

import org.springframework.stereotype.Service;

import com.tasked.modular.user.service.UserService;

@Service
public class UserServiceImpl implements UserService {
    @Override
    public String getHello(){
        return "hello SpringBoot";
    }
}
