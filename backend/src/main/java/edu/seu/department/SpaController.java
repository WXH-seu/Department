package edu.seu.department;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** Keeps browser refreshes on Vue routes inside the single-page application. */
@Controller
public class SpaController {
    @GetMapping({"/workspace","/admin","/admin/{section}"})
    public String application() { return "forward:/index.html"; }
}
