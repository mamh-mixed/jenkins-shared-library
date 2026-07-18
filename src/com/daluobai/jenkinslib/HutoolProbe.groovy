package com.daluobai.jenkinslib;

import com.daluobai.jenkinslib.utils.StrUtils

import java.io.Serializable;

public class HutoolProbe implements Serializable {

  static String run() {
    return "Shared library OK, isBlank(' ')=" + StrUtils.isBlank(" ")
  }
}
