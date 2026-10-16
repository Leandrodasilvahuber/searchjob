package com.huber.searchjob.db;

public record Candidatura(Long id, String portal, String empresa, String cargo, String url,
                          String cvUsado, String status, String motivo, String data) {}
