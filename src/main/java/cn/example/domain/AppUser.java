package cn.example.domain;

public record AppUser(long id, String username, String passwordHash, boolean enabled) {}
