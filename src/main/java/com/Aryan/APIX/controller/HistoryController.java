package com.Aryan.APIX.controller;

import com.Aryan.APIX.entity.RequestHistory;
import com.Aryan.APIX.service.HistoryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/apix")
public class HistoryController {

    @Autowired
    private HistoryService historyService;

    @GetMapping("/history")
    public List<RequestHistory> getHistory(){
        return historyService.getAllHistory();
    }
}
