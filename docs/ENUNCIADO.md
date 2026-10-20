# Enunciado do Trabalho Prático

> Texto do professor, transcrito integralmente para consulta. **Não é o texto do relatório** e não
> deve ser reescrito por IA; o relatório é escrito pelo grupo (ver `docs/ARQUITETURA.md` e o
> `AGENTS.md`).

# PUC Minas
**PONTIFÍCIA UNIVERSIDADE CATÓLICA DE MINAS GERAIS**  
**Instituto de Ciências Exatas e Informática**  
**Computação Distribuída**

# Trabalho Prático
**Prof. Luigi D. C. Soares**  
*Engenharia de Computação, Coração Eucarístico, 2026.2*

---

**Objetivo.** O objetivo deste trabalho prático é permitir que os alunos se aprofundem no estudo e desenvolvimento de sistemas distribuídos. Os alunos deverão se organizar em grupos e, em conjunto, projetar e implementar uma solução distribuída para resolver um problema de sua escolha, dentro do contexto de computação distribuída.

## 1 Formação de grupos
Para o desenvolvimento deste trabalho prático, os alunos devem formar grupos de 4 a 5 pessoas. Cada grupo escolherá um tema para o desenvolvimento do sistema distribuído.

## 2 Escolha do tema
**O tema do trabalho será escolhido pelo próprio grupo, podendo ser uma das opções sugeridas abaixo ou um tema alternativo aprovado pelo professor.** O sistema distribuído desenvolvido deverá implementar algum tipo de problema computacional complexo que pode ser dividido em várias unidades de trabalho que são executadas de maneira distribuída em diferentes máquinas.

### Sugestões de temas (exemplos):
1. **Algoritmo Genético Distribuído:** Desenvolver um sistema distribuído que execute um algoritmo genético com o objetivo de encontrar uma solução para um determinado problema. Neste caso, cada grupo de nós do sistema poderá ser responsável por uma parte da população e a troca de indivíduos será feita entre nós, simulando a evolução de forma distribuída.
2. **Algoritmo de Colônia de Formigas Distribuído:** Implementar um sistema distribuído que execute um algoritmo de colônia de formigas, onde cada nó do sistema pode simular um grupo de formigas que busca por soluções, por exemplo.
3. **Algoritmo de Segmentação de Imagens Distribuído:** Criar um sistema distribuído que aplique algoritmos de segmentação de imagens, como o algoritmo SLIC (Simple Linear Iterative Clustering), que deve ser distribuído entre várias máquinas para acelerar o processamento de grandes imagens, por exemplo.
4. **Simulação de um Sistema de Comunicação entre Nós Distribuídos:** Criar um sistema distribuído em que múltiplos nós se comunicam para resolver um problema em tempo real, como uma rede de sensores.
5. **Resolução de um Problema de Otimização:** Desenvolver um sistema distribuído que utilize técnicas de otimização, dividindo as tarefas entre múltiplos nós.
6. **Algoritmos de Machine Learning Distribuídos:** Desenvolver um sistema distribuído para aprendizado e/ou análise de grandes volumes de dados em tempo real, utilizando algoritmos de aprendizado de máquina.
7. **Desenvolvimento de um Sistema de Banco de Dados Distribuído:** Implementar um sistema de banco de dados simples com replicação e consistência distribuída, onde as informações são armazenadas e acessadas por vários nós do sistema.
8. **Desenvolvimento de um Sistema de Jogo "Online":** Criar um sistema para um jogo multiplayer online, onde os jogadores se conectam a servidores distribuídos, e o estado do jogo é compartilhado entre vários nós.
9. **Sistema de Recomendação Distribuído:** Criar um sistema de recomendação distribuído para sugerir produtos, músicas, filmes, entre outros. O sistema deve ser capaz de processar grandes volumes de dados e entregar recomendações personalizadas de forma eficiente, utilizando técnicas de aprendizado de máquina ou filtragem colaborativa, por exemplo.

## 3 Requisitos
1. O sistema deve ser distribuído, com pelo menos dois nós, podendo ser executado em máquinas conectadas à uma rede local.
2. O sistema deve ser capaz de realizar operações paralelizadas de forma eficiente e lidar com falhas de comunicação entre os nós.
3. Os alunos podem utilizar bibliotecas já disponíveis para facilitar a comunicação entre máquinas. **Contudo, cada grupo deve implementar manualmente alguns algoritmos/protocolos de acordo com os requisitos de cada entrega, conforme detalhado a seguir.**

## 4 Entregas

### 4.1 Primeira entrega
**Requisitos.** Os alunos deverão incorporar na implementação **pelo menos dois** dos seguintes requisitos:
* Utilizar RPC (sugestão: framework gRPC https://grpc.io/).
* Implementar um método de sincronização de relógios físicos.
* Implementar um método de sincronização de relógios lógicos.
* Implementar um método de eleição de líder.
* Implementar um método de controle de concorrência/exclusão mútua distribuída.
* Utilização de *web services*.

**Entrega.** Relatório PDF de **até 4 páginas**, seguindo o template de artigo da SBC, contendo (no mínimo):
* Explicação do problema escolhido.
* Detalhamento da arquitetura do sistema. (quem troca mensagem com quem e qual o conteúdo das principais mensagens, qual o papel de cada entidade, etc.).
* Detalhamento da implementação dos requisitos escolhidos na seção anterior.
* Desafios e problemas encontrados durante a implementação.
* Um breve resumo sobre o papel de cada aluno na condução do trabalho.

### 4.2 Segunda entrega
**Requisitos.** Na segunda entrega, os alunos deverão avançar sobre a base construída na primeira etapa. Para esta fase, **é obrigatório implementar métodos adequados para o tratamento de falhas no sistema**. Além disso, os grupos deverão escolher **no mínimo um** dos seguintes requisitos para expandir o projeto:
* Implementação de algoritmos para controle de réplicas.
* Implementação de algoritmos para garantia de consistência em transações distribuídas (por exemplo, Two-Phase Commit - 2PC).
* Realizar uma análise do desempenho do sistema (quando cabível) se comparado, por exemplo, à execução do algoritmo escolhido em uma única máquina.

**Entrega.** Os alunos deverão expandir o relatório da entrega anterior, ampliando o conteúdo para **até 6 páginas**, seguindo o mesmo template da entrega anterior e acrescentando (no mínimo) ao conteúdo anterior:
* Detalhamento dos métodos de tratamento de falha implementados e testes realizados.
* Detalhamento da implementação dos requisitos escolhidos na seção anterior.
* Conclusão e possíveis melhorias para o futuro.
* *Observação: o texto anterior pode ser modificado (ou até resumido, caso necessário) a vontade para se adaptar ao novo texto sendo incluído. Mantenha as seções da entrega anterior, incluindo uma atualização sobre o papel de cada aluno e sobre os desafios encontrados.*

Além do relatório, para a segunda entrega os alunos também devem enviar o código-fonte implementado, junto de instruções de como executá-lo.

**Apresentação.** Para a segunda entrega, cada grupo deverá realizar uma apresentação de até 10 minutos, demonstrando brevemente como o sistema foi desenvolvido, explicando a arquitetura, demonstrando o funcionamento do sistema e os resultados obtidos.

**Observação importante:** Durante a apresentação, espera-se que todos os alunos do grupo sejam capazes de responder perguntas básicas a respeito do sistema implementado em um contexto geral. Além disso, cada aluno deve ser capaz de responder com clareza às perguntas relacionadas especificamente às partes do trabalho em que atuou diretamente. Caso os alunos não consigam responder, a nota do grupo como um todo poderá ser prejudicada.

## 5 Avaliação
A avaliação do trabalho será feita com base nos seguintes critérios:
1. Qualidade e clareza da documentação técnica.
   * **Importante:** O uso de ferramentas de IA é válido como forma de auxiliar na escrita, corrigindo erros ou apontando melhorias, por exemplo. Entregas com textos (praticamente) gerados de forma automática serão desconsideradas. A capacidade dos alunos saberem organizar suas ideias em texto também é avaliada.
2. Originalidade e complexidade do tema escolhido.
   * **Observação:** Grupos que optarem por se arriscar mais e lidar com problemas mais complexos serão recompensados, independentemente do funcionamento/desempenho final do sistema. Grupos que optarem por implementações mais simples serão avaliados de forma proporcional, conforme o critério do professor.
3. Implementação conforme os requisitos listados anteriormente.
4. Funcionamento do sistema distribuído.
5. Qualidade e clareza da apresentação.
6. Capacidade do grupo de responder às perguntas realizadas pelo professor.

## 6 Data e pontuação
* **Entrega 1:**
  * Data de entrega: consultar a tarefa cadastrada no Canvas.
  * Pontuação: 10 pontos.
* **Entrega 2:**
  * Data de entrega: consultar a tarefa cadastrada no Canvas.
  * Data de apresentação: consultar o dia previsto no arquivo de plano de disciplina no Canvas.
  * Pontuação: 20 pontos.
