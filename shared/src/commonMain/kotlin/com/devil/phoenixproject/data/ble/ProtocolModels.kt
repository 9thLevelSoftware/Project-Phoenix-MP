package com.devil.phoenixproject.data.ble

import com.devil.phoenixproject.framework.protocol.DiagnosticPacket as FrameworkDiagnosticPacket
import com.devil.phoenixproject.framework.protocol.MonitorPacket as FrameworkMonitorPacket

/** Backward-compatible aliases for packet models moved to framework.protocol. */
typealias MonitorPacket = FrameworkMonitorPacket
typealias DiagnosticPacket = FrameworkDiagnosticPacket
