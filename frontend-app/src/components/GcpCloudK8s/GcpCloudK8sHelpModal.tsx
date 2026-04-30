/**
 * GCP CloudK8s Help Modal
 *
 * Delegates to GkeHelpModal — the full agent-based setup guide.
 */

import React from 'react';
import GkeHelpModal from '../GkeDetails/GkeHelpModal';

interface GcpCloudK8sHelpModalProps {
  isOpen: boolean;
  onClose: () => void;
}

const GcpCloudK8sHelpModal: React.FC<GcpCloudK8sHelpModalProps> = ({ isOpen, onClose }) => (
  <GkeHelpModal isOpen={isOpen} onClose={onClose} />
);

export default GcpCloudK8sHelpModal;
