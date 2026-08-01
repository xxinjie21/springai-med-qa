/**
 * Serialization / deserialization of chat memory entities.
 *
 * <p>The single codec in this package bridges the domain entities
 * ({@code ChatMessageDO} / {@code ChatSessionDO}) and the Protobuf wire schema declared in
 * {@code med_session.proto}. Protobuf binary is the on-disk and in-cache format mandated by the
 * unified medical storage specification (ROADMAP section 4), which makes the data written here
 * readable by the heterogeneous Python middleware and vice versa.</p>
 */
package com.med.qa.memory.serde;
