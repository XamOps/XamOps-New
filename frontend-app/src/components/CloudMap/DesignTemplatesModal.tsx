import React from 'react';

interface Template {
  id: string;
  name: string;
  category: string;
  description: string;
  thumbnail: string;
  elements: any[];
}

const TEMPLATES: Template[] = [
  {
    id: 'aws-web-app',
    name: 'AWS Web Application',
    category: 'AWS',
    description: '3-tier web app: ALB → EC2 → RDS',
    thumbnail: '☁️',
    elements: [
      { group: 'nodes', data: { id: 'n1', label: 'Internet', type: 'Internet Gateway', fillColor: '#f0f9ff', borderColor: '#0ea5e9' }, position: { x: 300, y: 80 } },
      { group: 'nodes', data: { id: 'n2', label: 'ALB', type: 'Load Balancer', fillColor: '#f0fdf4', borderColor: '#16a34a' }, position: { x: 300, y: 200 } },
      { group: 'nodes', data: { id: 'n3', label: 'EC2 Instance', type: 'EC2 Instance', fillColor: '#fff7ed', borderColor: '#ea580c' }, position: { x: 200, y: 340 } },
      { group: 'nodes', data: { id: 'n4', label: 'EC2 Instance', type: 'EC2 Instance', fillColor: '#fff7ed', borderColor: '#ea580c' }, position: { x: 400, y: 340 } },
      { group: 'nodes', data: { id: 'n5', label: 'RDS', type: 'RDS Instance', fillColor: '#faf5ff', borderColor: '#9333ea' }, position: { x: 300, y: 480 } },
      { group: 'edges', data: { id: 'e1', source: 'n1', target: 'n2', label: 'HTTPS' } },
      { group: 'edges', data: { id: 'e2', source: 'n2', target: 'n3' } },
      { group: 'edges', data: { id: 'e3', source: 'n2', target: 'n4' } },
      { group: 'edges', data: { id: 'e4', source: 'n3', target: 'n5' } },
      { group: 'edges', data: { id: 'e5', source: 'n4', target: 'n5' } },
    ],
  },
  {
    id: 'microservices',
    name: 'Microservices',
    category: 'Architecture',
    description: 'API Gateway → Services → DB',
    thumbnail: '⚙️',
    elements: [
      { group: 'nodes', data: { id: 'n1', label: 'Client', type: 'Amazon Cognito', fillColor: '#f3f4f6', borderColor: '#6b7280' }, position: { x: 300, y: 80 } },
      { group: 'nodes', data: { id: 'n2', label: 'API Gateway', type: 'API Gateway', fillColor: '#f0f9ff', borderColor: '#0ea5e9' }, position: { x: 300, y: 200 } },
      { group: 'nodes', data: { id: 'n3', label: 'Auth Service', type: 'Lambda Function', fillColor: '#fff7ed', borderColor: '#ea580c' }, position: { x: 150, y: 340 } },
      { group: 'nodes', data: { id: 'n4', label: 'User Service', type: 'Lambda Function', fillColor: '#f0fdf4', borderColor: '#16a34a' }, position: { x: 300, y: 340 } },
      { group: 'nodes', data: { id: 'n5', label: 'Order Service', type: 'Lambda Function', fillColor: '#faf5ff', borderColor: '#9333ea' }, position: { x: 450, y: 340 } },
      { group: 'nodes', data: { id: 'n6', label: 'Auth DB', type: 'RDS Instance', fillColor: '#fff1f2', borderColor: '#e11d48' }, position: { x: 150, y: 480 } },
      { group: 'nodes', data: { id: 'n7', label: 'User DB', type: 'RDS Instance', fillColor: '#f0fdf4', borderColor: '#16a34a' }, position: { x: 300, y: 480 } },
      { group: 'nodes', data: { id: 'n8', label: 'Order DB', type: 'DynamoDB Table', fillColor: '#faf5ff', borderColor: '#9333ea' }, position: { x: 450, y: 480 } },
      { group: 'edges', data: { id: 'e1', source: 'n1', target: 'n2' } },
      { group: 'edges', data: { id: 'e2', source: 'n2', target: 'n3' } },
      { group: 'edges', data: { id: 'e3', source: 'n2', target: 'n4' } },
      { group: 'edges', data: { id: 'e4', source: 'n2', target: 'n5' } },
      { group: 'edges', data: { id: 'e5', source: 'n3', target: 'n6' } },
      { group: 'edges', data: { id: 'e6', source: 'n4', target: 'n7' } },
      { group: 'edges', data: { id: 'e7', source: 'n5', target: 'n8' } },
    ],
  },
  {
    id: 'er-diagram',
    name: 'ER Diagram',
    category: 'Database',
    description: 'User-Order-Product ER schema',
    thumbnail: '🗄️',
    elements: [
      { group: 'nodes', data: { id: 'n1', label: 'User', type: 'DynamoDB Table', nodeShape: 'rectangle', fillColor: '#ffffff', borderColor: '#374151', borderWidth: 2 }, position: { x: 160, y: 200 } },
      { group: 'nodes', data: { id: 'n2', label: 'Places', nodeShape: 'diamond', fillColor: '#f3f4f6', borderColor: '#374151' }, position: { x: 300, y: 200 } },
      { group: 'nodes', data: { id: 'n3', label: 'Order', type: 'DynamoDB Table', nodeShape: 'rectangle', fillColor: '#ffffff', borderColor: '#374151', borderWidth: 2 }, position: { x: 440, y: 200 } },
      { group: 'nodes', data: { id: 'n4', label: 'Contains', nodeShape: 'diamond', fillColor: '#f3f4f6', borderColor: '#374151' }, position: { x: 440, y: 340 } },
      { group: 'nodes', data: { id: 'n5', label: 'Product', type: 'DynamoDB Table', nodeShape: 'rectangle', fillColor: '#ffffff', borderColor: '#374151', borderWidth: 2 }, position: { x: 440, y: 480 } },
      { group: 'nodes', data: { id: 'n6', label: 'user_id\n(PK)', nodeShape: 'ellipse', fillColor: '#f3f4f6', borderColor: '#374151' }, position: { x: 80, y: 340 } },
      { group: 'edges', data: { id: 'e1', source: 'n1', target: 'n2', label: '1' } },
      { group: 'edges', data: { id: 'e2', source: 'n2', target: 'n3', label: 'N' } },
      { group: 'edges', data: { id: 'e3', source: 'n3', target: 'n4', label: '1' } },
      { group: 'edges', data: { id: 'e4', source: 'n4', target: 'n5', label: 'N' } },
      { group: 'edges', data: { id: 'e5', source: 'n6', target: 'n1' } },
    ],
  },
  {
    id: 'flowchart',
    name: 'Decision Flowchart',
    category: 'Flowchart',
    description: 'Basic decision process flow',
    thumbnail: '📊',
    elements: [
      { group: 'nodes', data: { id: 'n1', label: 'Start', type: 'AWS Step Functions', nodeShape: 'ellipse', fillColor: '#dcfce7', borderColor: '#16a34a' }, position: { x: 300, y: 80 } },
      { group: 'nodes', data: { id: 'n2', label: 'Process Step', type: 'Lambda Function', nodeShape: 'rectangle', fillColor: '#f0f9ff', borderColor: '#0ea5e9' }, position: { x: 300, y: 200 } },
      { group: 'nodes', data: { id: 'n3', label: 'Decision?', type: 'CloudWatch Log Group', nodeShape: 'diamond', fillColor: '#fef9c3', borderColor: '#d97706' }, position: { x: 300, y: 340 } },
      { group: 'nodes', data: { id: 'n4', label: 'Action A', type: 'Lambda Function', nodeShape: 'rectangle', fillColor: '#f0f9ff', borderColor: '#0ea5e9' }, position: { x: 160, y: 480 } },
      { group: 'nodes', data: { id: 'n5', label: 'Action B', type: 'Lambda Function', nodeShape: 'rectangle', fillColor: '#f0f9ff', borderColor: '#0ea5e9' }, position: { x: 440, y: 480 } },
      { group: 'nodes', data: { id: 'n6', label: 'End', type: 'SNS Topic', nodeShape: 'ellipse', fillColor: '#fee2e2', borderColor: '#dc2626' }, position: { x: 300, y: 620 } },
      { group: 'edges', data: { id: 'e1', source: 'n1', target: 'n2' } },
      { group: 'edges', data: { id: 'e2', source: 'n2', target: 'n3' } },
      { group: 'edges', data: { id: 'e3', source: 'n3', target: 'n4', label: 'Yes' } },
      { group: 'edges', data: { id: 'e4', source: 'n3', target: 'n5', label: 'No' } },
      { group: 'edges', data: { id: 'e5', source: 'n4', target: 'n6' } },
      { group: 'edges', data: { id: 'e6', source: 'n5', target: 'n6' } },
    ],
  },
  {
    id: 'uml-class',
    name: 'UML Class Diagram',
    category: 'UML',
    description: 'Inheritance and composition',
    thumbnail: '📐',
    elements: [
      { group: 'nodes', data: { id: 'n1', label: 'Animal\n—————\n+ name: string\n—————\n+ speak(): void', type: 'ECS Cluster', nodeShape: 'rectangle', fillColor: '#ffffff', borderColor: '#374151' }, position: { x: 300, y: 160 } },
      { group: 'nodes', data: { id: 'n2', label: 'Dog\n—————\n+ breed: string\n—————\n+ speak(): void', type: 'ECS Cluster', nodeShape: 'rectangle', fillColor: '#ffffff', borderColor: '#374151' }, position: { x: 160, y: 360 } },
      { group: 'nodes', data: { id: 'n3', label: 'Cat\n—————\n+ indoor: boolean\n—————\n+ speak(): void', type: 'ECS Cluster', nodeShape: 'rectangle', fillColor: '#ffffff', borderColor: '#374151' }, position: { x: 440, y: 360 } },
      { group: 'edges', data: { id: 'e1', source: 'n2', target: 'n1', label: 'extends', arrowType: 'triangle' } },
      { group: 'edges', data: { id: 'e2', source: 'n3', target: 'n1', label: 'extends', arrowType: 'triangle' } },
    ],
  },
  {
    id: 'network-topology',
    name: 'Network Topology',
    category: 'Network',
    description: 'Core router, switches, endpoints',
    thumbnail: '🌐',
    elements: [
      { group: 'nodes', data: { id: 'n1', label: 'Core Router', type: 'Internet Gateway', fillColor: '#f0f9ff', borderColor: '#0ea5e9' }, position: { x: 300, y: 160 } },
      { group: 'nodes', data: { id: 'n2', label: 'Switch A', type: 'VPC', fillColor: '#f0fdf4', borderColor: '#16a34a' }, position: { x: 160, y: 300 } },
      { group: 'nodes', data: { id: 'n3', label: 'Switch B', type: 'VPC', fillColor: '#f0fdf4', borderColor: '#16a34a' }, position: { x: 440, y: 300 } },
      { group: 'nodes', data: { id: 'n4', label: 'Server 1', type: 'EC2 Instance', fillColor: '#fff7ed', borderColor: '#ea580c' }, position: { x: 80, y: 440 } },
      { group: 'nodes', data: { id: 'n5', label: 'Server 2', type: 'EC2 Instance', fillColor: '#fff7ed', borderColor: '#ea580c' }, position: { x: 240, y: 440 } },
      { group: 'nodes', data: { id: 'n6', label: 'Server 3', type: 'EC2 Instance', fillColor: '#fff7ed', borderColor: '#ea580c' }, position: { x: 360, y: 440 } },
      { group: 'nodes', data: { id: 'n7', label: 'Server 4', type: 'EC2 Instance', fillColor: '#fff7ed', borderColor: '#ea580c' }, position: { x: 520, y: 440 } },
      { group: 'edges', data: { id: 'e1', source: 'n1', target: 'n2' } },
      { group: 'edges', data: { id: 'e2', source: 'n1', target: 'n3' } },
      { group: 'edges', data: { id: 'e3', source: 'n2', target: 'n4' } },
      { group: 'edges', data: { id: 'e4', source: 'n2', target: 'n5' } },
      { group: 'edges', data: { id: 'e5', source: 'n3', target: 'n6' } },
      { group: 'edges', data: { id: 'e6', source: 'n3', target: 'n7' } },
    ],
  },
];

interface Props {
  onClose: () => void;
  onApply: (elements: any[]) => void;
}

const CATEGORIES = ['All', 'AWS', 'Architecture', 'Database', 'Flowchart', 'UML', 'Network'];

const DesignTemplatesModal: React.FC<Props> = ({ onClose, onApply }) => {
  const [activeCategory, setActiveCategory] = React.useState('All');
  const filtered = activeCategory === 'All' ? TEMPLATES : TEMPLATES.filter(t => t.category === activeCategory);

  return (
    <div
      className="fixed inset-0 z-[400] flex items-center justify-center bg-black/40 backdrop-blur-sm"
      onClick={(e) => { if (e.target === e.currentTarget) onClose(); }}
    >
      <div className="bg-white rounded-2xl shadow-2xl border border-gray-100 w-[90vw] max-w-3xl max-h-[85vh] flex flex-col overflow-hidden">
        <div className="flex items-center justify-between px-6 py-4 border-b border-gray-100 shrink-0">
          <div className="flex items-center gap-3">
            <div className="w-9 h-9 rounded-xl bg-gray-900 flex items-center justify-center">
              <i className="fas fa-th-large text-white text-base" />
            </div>
            <div>
              <h2 className="text-base font-bold text-gray-900">Diagram Templates</h2>
              <p className="text-[11px] text-gray-400">{TEMPLATES.length} templates • click to apply</p>
            </div>
          </div>
          <button
            onClick={onClose}
            className="w-8 h-8 flex items-center justify-center rounded-lg hover:bg-gray-100 text-gray-400 hover:text-gray-700"
          >
            <i className="fas fa-times" />
          </button>
        </div>

        <div className="flex items-center gap-1.5 px-6 py-3 border-b border-gray-100 shrink-0 overflow-x-auto">
          {CATEGORIES.map(cat => (
            <button
              key={cat}
              onClick={() => setActiveCategory(cat)}
              className={`px-3 py-1 rounded-full text-[11px] font-semibold whitespace-nowrap transition-all ${
                activeCategory === cat
                  ? 'bg-gray-900 text-white'
                  : 'bg-gray-100 text-gray-600 hover:bg-gray-200'
              }`}
            >
              {cat}
            </button>
          ))}
        </div>

        <div className="flex-1 overflow-y-auto p-6 grid grid-cols-1 sm:grid-cols-2 md:grid-cols-3 gap-4">
          {filtered.map(t => (
            <div
              key={t.id}
              onClick={() => {
                if (window.confirm(`Apply "${t.name}" template? This will add elements to your canvas.`)) {
                  onApply(t.elements);
                  onClose();
                }
              }}
              className="border border-gray-200 rounded-xl p-4 cursor-pointer hover:border-gray-400 hover:shadow-md transition-all group"
            >
              <div className="text-3xl mb-3">{t.thumbnail}</div>
              <h3 className="text-sm font-bold text-gray-800 mb-1 group-hover:text-gray-900">{t.name}</h3>
              <p className="text-[11px] text-gray-500 mb-2">{t.description}</p>
              <span className="inline-block px-2 py-0.5 bg-gray-100 text-[10px] font-semibold text-gray-600 rounded-full">
                {t.category}
              </span>
            </div>
          ))}
        </div>
      </div>
    </div>
  );
};

export default DesignTemplatesModal;
