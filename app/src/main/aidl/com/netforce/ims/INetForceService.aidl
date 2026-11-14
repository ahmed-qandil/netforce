package com.netforce.ims;

import android.os.Bundle;

/**
 * Privileged backend bridge: runs in a standalone Shizuku UserService process
 * (shell uid). One generic op-dispatch method keeps the AIDL surface minimal.
 */
interface INetForceService {
    Bundle execute(String op, in Bundle args);
    void destroy();
}
